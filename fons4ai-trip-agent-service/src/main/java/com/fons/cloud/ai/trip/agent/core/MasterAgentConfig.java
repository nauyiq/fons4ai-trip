package com.fons.cloud.ai.trip.agent.core;

import com.fons.cloud.ai.agent.api.Agent;
import com.fons.cloud.ai.agent.api.AgentType;
import com.fons.cloud.ai.agent.core.AgentScopeHarnessAgent;
import com.fons.cloud.ai.agent.core.AgentTaskManager;
import com.fons.cloud.ai.agent.infrastructure.middleware.ActiveAgentPersistenceMiddleware;
import com.fons.cloud.ai.agent.infrastructure.middleware.AgentScopeApprovalRejectMiddleware;
import com.fons.cloud.ai.agent.infrastructure.middleware.AgentScopeExecutionLifecycleMiddleware;
import com.fons.cloud.ai.agent.infrastructure.middleware.FonsAgentTraceMiddleware;
import com.fons.cloud.ai.trip.agent.mcp.WeatherMcp;
import com.fons.cloud.ai.trip.infrastructure.middleware.AgentScopeDelegationMiddleware;
import com.fons.cloud.ai.trip.infrastructure.agent.delegation.AgentScopeDelegationPolicy;
import com.fons.cloud.ai.trip.infrastructure.middleware.AnalysisAgentMiddleware;
import com.fons.cloud.ai.trip.infrastructure.config.properties.CompressConfig;
import com.fons.cloud.ai.trip.infrastructure.config.properties.MasterAgentConfigProperties;
import com.fons.cloud.ai.trip.agent.tool.*;
import com.fons.cloud.ai.trip.infrastructure.util.ModelFacade;
import com.fons.cloud.ai.trip.infrastructure.middleware.TripTimeContextMiddleware;
import com.fons.cloud.ai.trip.infrastructure.middleware.TripToolExecutionGuardMiddleware;
import com.fons.cloud.ai.trip.infrastructure.agent.prompt.PromptLoader;
import com.fons.cloud.common.exception.ExceptionCode;
import com.fons.cloud.common.exception.ExceptionType;
import com.fons.cloud.common.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
import io.agentscope.core.tool.ToolBase;
import io.agentscope.core.tracing.OtelTracingMiddleware;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Qualifier;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * 主Agent，负责Agent的路由/多意图识别/结果整合等
 *
 * @author hongqy
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MasterAgentConfigProperties.class)
public class MasterAgentConfig {

    /** Agent 配置异常所属模块。 */
    private static final String EXCEPTION_MODULE = "TRIP";

    /**
     * 关键业务写操作逐次 ASK，配置只能收紧为 DENY。
     */
    private static final Set<String> REQUIRED_APPROVAL_TOOLS = Set.of("submit_travel_approval", "modify_travel_order",
            "cancel_travel_order", "update_user_contact_info", "update_user_base_location", "create_itinerary_plan_page");
    /**
     * SDK 2.0.3 的记忆、协调与只读文件工具；显式 ALLOW，不依赖“未列入 ASK”。
     */
    private static final Set<String> NATIVE_ALLOWED_TOOLS = Set.of("memory_save", "memory_get", "memory_search",
            "agent_spawn", "agent_send", "agent_list", "task_list", "task_output", "task_cancel", "wait_async_results",
            "todo_write", "read_file", "grep_files", "glob_files", "list_files");
    /**
     * 业务不开放通用命令与任意文件修改，原生记忆内部 I/O 不受该工具名单影响。
     */
    private static final Set<String> DENIED_TOOLS = Set.of("execute", "shell", "bash", "execute_command", "run_command",
            "file_write", "file_edit", "file_append", "file_delete", "write_file", "edit_file", "execute_python");

    private final MasterAgentConfigProperties properties;
    private final DistributedStore distributedStore;
    private final AgentTaskManager agentTaskManager;
    private final UserPreferenceTools userPreferenceTools;

    // == middleware ==
    // 这里表示启用框架层提供的可观测性，配置文件里面一定要有sys.agent.observability的配置
    private final FonsAgentTraceMiddleware fonsAgentTraceMiddleware;
    // 为每轮请求提供可信日期 父子Agent公用
    private final TripTimeContextMiddleware tripTimeContextMiddleware;
    // 将Pipeline分析结果临时注入MasterAgent的模型输入
    private final AnalysisAgentMiddleware analysisAgentMiddleware;
    // 持久化当前会话Agent身份的中间件
    private final ActiveAgentPersistenceMiddleware activeAgentPersistenceMiddleware;

    // == 工具清单 ==
    private final BookingReadTools bookingReadTools;
    private final TravelOrderReadTools travelOrderReadTools;
    private final TravelOrderWriteTools travelOrderWriteTools;
    private final TravelOrderConflictTools travelOrderConflictTools;
    private final UserInfoReadTools userInfoReadTools;
    private final UserInfoWriteTools userInfoWriteTools;
    private final PolicyTools policyTools;
    private final ItinerarySearchTools itinerarySearchTools;
    private final ItineraryPlannerTools itineraryPlannerTools;
    private final ItineraryPlanReadTools itineraryPlanReadTools;
    private final DestinationLiveTools destinationLiveTools;
    private final VisaTools visaTools;

    // == MCP清单 ==
    private final WeatherMcp weatherMcp;

    /**
     * 从三个实际 Toolkit 构建分类清单；内部文件、待办、委派工具不会因名称相似进入业务集合。
     * MCP 使用已注册的真实工具名，避免配置白名单与 SDK 命名前缀不一致。
     */
    @Bean
    public TripToolCatalog tripToolCatalog(@Qualifier("itineraryManageAgent") HarnessAgent itineraryManageAgent,
                                           @Qualifier("itineraryPlanAgent") HarnessAgent itineraryPlanAgent,
                                           @Qualifier("infoAgent") HarnessAgent infoAgent) {
        Set<String> declared = Stream.of(bookingReadTools, travelOrderReadTools, travelOrderWriteTools,
                        travelOrderConflictTools, userInfoReadTools, userInfoWriteTools, policyTools,
                        itinerarySearchTools, itineraryPlannerTools, itineraryPlanReadTools, destinationLiveTools, visaTools,
                        userPreferenceTools)
                .flatMap(group -> group.tools().stream()).collect(Collectors.toSet());
        Set<String> registered = Stream.of(itineraryManageAgent, itineraryPlanAgent, infoAgent)
                .map(agent -> agent.getDelegate().getToolkit())
                .flatMap(toolkit -> toolkit.getToolNames().stream()
                        .filter(name -> declared.contains(name)
                                || toolkit.getTool(name) instanceof ToolBase tool && tool.isMcp()))
                .collect(Collectors.toUnmodifiableSet());
        validatePermissionConfiguration(Stream.of(itineraryManageAgent, itineraryPlanAgent, infoAgent)
                .flatMap(agent -> agent.getToolkit().getToolNames().stream()).collect(Collectors.toSet()), declared);
        return new TripToolCatalog(registered);
    }

    @Bean(name = "itineraryManageAgent")
    public HarnessAgent itineraryManageAgent() {
        log.info("[MasterAgent] 开始创建行程管理子Agent...");
        // 创建工具集
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(bookingReadTools);
        toolkit.registerTool(travelOrderReadTools);
        toolkit.registerTool(travelOrderWriteTools);
        toolkit.registerTool(travelOrderConflictTools);
        toolkit.registerTool(userInfoReadTools);
        toolkit.registerTool(userInfoWriteTools);
        registerPreferences(toolkit, true);
        // 子AGENT构建
        HarnessAgent itineraryManageAgent = childBuilder(toolkit)
                .name(TripAgent.ITINERARY_MANAGE_AGENT.getAgentName())
                .description(TripAgent.ITINERARY_MANAGE_AGENT.getDescription())
                .sysPrompt(systemPrompt("prompt/itinerary-manage-agent-system.md"))
                .middleware(businessToolGuard(Set.of(), bookingReadTools, travelOrderReadTools,
                        travelOrderWriteTools, travelOrderConflictTools, userInfoReadTools, userInfoWriteTools, userPreferenceTools))
                .disableSubagents()
                .build();
        log.info("[MasterAgent] 行程管理子Agent创建成功...");
        return itineraryManageAgent;
    }

    @Bean(name = "itineraryPlanAgent")
    public HarnessAgent itineraryPlanAgent() {
        log.info("[MaserAgent] 开始创建行程规划子Agent...");
        // 创建工具集
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(bookingReadTools);
        toolkit.registerTool(userInfoReadTools);
        toolkit.registerTool(userInfoWriteTools);
        toolkit.registerTool(travelOrderReadTools);
        toolkit.registerTool(destinationLiveTools);
        toolkit.registerTool(policyTools);
        toolkit.registerTool(itinerarySearchTools);
        toolkit.registerTool(itineraryPlannerTools);
        toolkit.registerTool(itineraryPlanReadTools);
        registerPreferences(toolkit, false);
        if (weatherMcp.getMcpClient() != null) {
            toolkit.registration().mcpClient(weatherMcp.getMcpClient())
                    .enableTools(weatherMcp.getEnabledTools())
                    .apply();
        }

        // 子AGENT构建
        HarnessAgent itineraryPlanAgent = childBuilder(toolkit)
                .name(TripAgent.ITINERARY_PLAN_AGENT.getAgentName())
                .description(TripAgent.ITINERARY_PLAN_AGENT.getDescription())
                .sysPrompt(systemPrompt("prompt/itinerary-plan-agent-system.md"))
                .enableTaskList(true)
                // 天气和目的地资讯属于可选补充，其失败不能把已完成的核心规划永久阻塞。
                .middleware(businessToolGuard(Set.copyOf(DestinationLiveTools.TOOLS), bookingReadTools,
                        userInfoReadTools, userInfoWriteTools, travelOrderReadTools, destinationLiveTools,
                        policyTools, itinerarySearchTools, itineraryPlannerTools, itineraryPlanReadTools, userPreferenceTools))
                .disableSubagents()
                .build();
        log.info("[MasterAgent] 行程规划子Agent创建成功...");
        return itineraryPlanAgent;
    }

    @Bean(name = "infoAgent")
    public HarnessAgent infoAgent() {
        log.info("[MasterAgent] 开始创建信息查询子Agent...");
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(policyTools);
        toolkit.registerTool(destinationLiveTools);
        toolkit.registerTool(visaTools);
        registerPreferences(toolkit, false);
        if (weatherMcp.getMcpClient() != null) {
            toolkit.registration().mcpClient(weatherMcp.getMcpClient())
                    .enableTools(weatherMcp.getEnabledTools())
                    .apply();
        }
        HarnessAgent infoAgent = childBuilder(toolkit)
                .name(TripAgent.INFO_AGENT.getAgentName())
                .description(TripAgent.INFO_AGENT.getDescription())
                .sysPrompt(systemPrompt("prompt/info-agent-system.md"))
                .middleware(businessToolGuard(Set.of(), policyTools, destinationLiveTools, visaTools, userPreferenceTools))
                .disableSubagents()
                .build();
        log.info("[MasterAgent] 信息查询子Agent创建成功...");
        return infoAgent;
    }

    @Bean(name = "masterAgent")
    public Agent masterAgent(HarnessAgent itineraryManageAgent,
                             HarnessAgent itineraryPlanAgent,
                             HarnessAgent infoAgent) {
        Toolkit masterToolkit = new Toolkit(ToolkitConfig.builder().parallel(false).build());
        registerPreferences(masterToolkit, true);
        HarnessAgent.Builder masterBuilder = commonBuilder()
                .name(TripAgent.MASTER_AGENT.getAgentName())
                .sysPrompt(systemPrompt("prompt/master_agent_sys_prompt.md"))
                .middleware(analysisAgentMiddleware)
                .middleware(businessToolGuard(Set.of(), userPreferenceTools))
                // 即使模型一次返回多个调用，也按顺序执行；子Agent仍使用各自的Toolkit。
                .toolkit(masterToolkit)
                .permissionContext(nativePermissions(masterToolkit));

        // 配置行程管理子Agent
        masterBuilder.subagentFactory(TripAgent.ITINERARY_MANAGE_AGENT.getAgentName(), TripAgent.ITINERARY_MANAGE_AGENT.getDescription(),
                name -> itineraryManageAgent);

        // 配置行程规划子Agent
        masterBuilder.subagentFactory(TripAgent.ITINERARY_PLAN_AGENT.getAgentName(), TripAgent.ITINERARY_PLAN_AGENT.getDescription(),
                name -> itineraryPlanAgent);

        masterBuilder.subagentFactory(TripAgent.INFO_AGENT.getAgentName(), TripAgent.INFO_AGENT.getDescription(),
                name -> infoAgent);

        // 中间件只处理委派技术约束；Agent装配和构建仍由配置类负责。
        MasterAgentConfigProperties.DelegationConfig delegation = properties.getDelegation();
        AgentScopeDelegationMiddleware delegationMiddleware = new AgentScopeDelegationMiddleware(
                new AgentScopeDelegationPolicy(delegation.isRequireInitialTask(),
                        delegation.isForceSync(), delegation.getSyncTimeoutSeconds(),
                        // 子审批由框架保留原委派并整批收口，不能再改写成普通ERROR。
                        false));
        masterBuilder.middleware(delegationMiddleware);

        // 保留fons4ai契约、任务管理、INPUT_REQUIRED及审批处理，不另建调度器。
        return AgentScopeHarnessAgent.builder()
                .agentName(TripAgent.MASTER_AGENT.getAgentName())
                .agentType(AgentType.HARNESS)
                .agentTaskManager(agentTaskManager)
                .delegateBuilder(masterBuilder)
                .inputRequiredEnabled(true)
                .subagentApprovalEnabled(true)
                .build();
    }

    /**
     * 只适配本Agent实际装配的Trip业务工具，文件、记忆、MCP及委派工具仍走SDK原生链路。
     * 单例中间件不持有请求计数；每次Agent调用的失败记录由RuntimeContext独立管理。
     */
    private TripToolExecutionGuardMiddleware businessToolGuard(Set<String> nonBlockingTodoTools,
                                                               BaseTool... toolGroups) {
        Set<String> toolNames = Arrays.stream(toolGroups)
                .flatMap(group -> group.tools().stream())
                .collect(Collectors.toUnmodifiableSet());
        return new TripToolExecutionGuardMiddleware(toolNames, properties.getToolFailureLimit(),
                nonBlockingTodoTools);
    }

    /**
     * 子审批直接使用SDK权限规则；框架负责收口与恢复，不把确认逻辑写进业务工具。
     * Master的拒绝中间件由框架安装，子Agent在此注册一次以推进拒绝后的原生状态。
     */
    private HarnessAgent.Builder childBuilder(Toolkit toolkit) {
        HarnessAgent.Builder builder = commonBuilder().toolkit(toolkit)
                .middleware(AgentScopeExecutionLifecycleMiddleware.getInstance())
                .middleware(AgentScopeApprovalRejectMiddleware.getInstance());
        // 与原 ASK 配置一样，只装配 SDK 的初始规则；旧会话继续由 SDK 恢复自身许可状态。
        return builder.permissionContext(nativePermissions(toolkit));
    }

    /**
     * 在 Trip 装配固定原生规则，不增加框架策略提供者或运行期替换。
     * Toolkit 中的业务工具按实际注册情况处理；构建后才加入的原生工具使用明确的基础名单。
     */
    private PermissionContextState nativePermissions(Toolkit toolkit) {
        Map<String, PermissionBehavior> rules = new HashMap<>();
        toolkit.getToolNames().forEach(name -> rules.put(name, PermissionBehavior.ASK));
        Stream.of(BookingReadTools.TOOLS, TravelOrderReadTools.TOOLS, TravelOrderConflictTools.TOOLS,
                        UserInfoReadTools.TOOLS, PolicyTools.TOOLS, ItinerarySearchTools.TOOLS,
                        ItineraryPlanReadTools.TOOLS, DestinationLiveTools.TOOLS, VisaTools.TOOLS,
                        UserPreferenceTools.TOOLS, List.of("plan_itinerary", "review_itinerary_plan"))
                .flatMap(List::stream).filter(name -> toolkit.getTool(name) != null)
                .forEach(name -> rules.put(name, PermissionBehavior.ALLOW));
        NATIVE_ALLOWED_TOOLS.forEach(name -> {
            var tool = toolkit.getTool(name);
            if (!(tool instanceof ToolBase nativeTool) || !nativeTool.isMcp()) {
                rules.put(name, PermissionBehavior.ALLOW);
            }
        });
        for (String name : weatherMcp.getEnabledTools()) {
            if (toolkit.getTool(name) instanceof ToolBase tool && tool.isMcp()
                    && "weather-mcp".equals(tool.getMcpName())) {
                rules.put(name, PermissionBehavior.ALLOW);
            }
        }
        REQUIRED_APPROVAL_TOOLS.stream().filter(name -> toolkit.getTool(name) != null)
                .forEach(name -> rules.put(name, PermissionBehavior.ASK));
        DENIED_TOOLS.forEach(name -> rules.put(name, PermissionBehavior.DENY));
        configuredPermissions().forEach((name, behavior) -> {
            if (rules.containsKey(name)) {
                rules.put(name, behavior);
            }
        });
        PermissionContextState.Builder permissions = PermissionContextState.builder().mode(PermissionMode.DEFAULT);
        rules.forEach((name, behavior) -> {
            PermissionRule rule = new PermissionRule(name, null, behavior, "Trip 原生工具许可配置");
            switch (behavior) {
                case ALLOW -> permissions.addAllowRule(name, rule);
                case ASK -> permissions.addAskRule(name, rule);
                case DENY -> permissions.addDenyRule(name, rule);
                default -> throw configurationError("工具许可只支持 ALLOW、ASK、DENY：" + name
                        + "，实际值：" + behavior);
            }
        });
        return permissions.build();
    }

    /**
     * 旧 ASK 名单仍是原入口；可选三态覆盖直接转成 SDK 规则，冲突或放宽强制规则则拒绝启动。
     */
    private Map<String, PermissionBehavior> configuredPermissions() {
        Map<String, PermissionBehavior> configured = new HashMap<>(properties.getToolPermissions().getOverrides());
        for (String name : properties.getApprovalRequiredTools()) {
            if (configured.containsKey(name) && configured.get(name) != PermissionBehavior.ASK) {
                throw configurationError("工具许可与旧 ASK 名单冲突：" + name);
            }
            configured.put(name, PermissionBehavior.ASK);
        }
        configured.forEach((name, behavior) -> {
            // SDK 还提供 PASSTHROUGH，Trip 的固定配置只允许明确三态，不能静默落回默认行为。
            if (behavior != PermissionBehavior.ALLOW
                    && behavior != PermissionBehavior.ASK
                    && behavior != PermissionBehavior.DENY) {
                throw configurationError("工具许可只支持 ALLOW、ASK、DENY：" + name);
            }
            if ((REQUIRED_APPROVAL_TOOLS.contains(name) && behavior == PermissionBehavior.ALLOW)
                    || (DENIED_TOOLS.contains(name) && behavior != PermissionBehavior.DENY)) {
                throw configurationError("不能降低业务强制工具许可：" + name);
            }
        });
        return configured;
    }

    private void validatePermissionConfiguration(Set<String> registered, Set<String> declaredBusiness) {
        Set<String> knownNative = new HashSet<>(NATIVE_ALLOWED_TOOLS);
        knownNative.addAll(DENIED_TOOLS);
        for (String name : configuredPermissions().keySet()) {
            if (registered.contains(name) || knownNative.contains(name)) {
                continue;
            }
            if (declaredBusiness.contains(name)) {
                log.warn("工具许可声明当前未装配的可选工具：{}", name);
            } else {
                throw configurationError("工具许可配置引用未知工具：" + name);
            }
        }
    }

    /** 将工具许可配置错误统一标识为内部配置失败。 */
    private static SystemIntervalException configurationError(String message) {
        return new SystemIntervalException(ExceptionCode.of(ResultCode.SYSTEM_INTERVAL_ERROR.getCode(),
                message, ExceptionType.SYSTEM, EXCEPTION_MODULE));
    }

    private HarnessAgent.Builder commonBuilder() {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .middleware(tripTimeContextMiddleware)
                // 原生中间件创建Agent、模型和工具Span；Fons中间件在同一上下文中补充业务事件。
                // Master与子Agent统一注册，确保整条调用链可由Langfuse接收和关联。
                .middleware(new OtelTracingMiddleware())
                .middleware(fonsAgentTraceMiddleware)
                .middleware(activeAgentPersistenceMiddleware)
                .model(ModelFacade.getModel(properties.getMainModel()))
                .workspace(Paths.get(properties.getWorkspace()))
                .distributedStore(distributedStore)
                // 父子明确使用同一共享状态存储；恢复不能隐式重放悬空的spawn/send。
                .stateStore(distributedStore.agentStateStore())
                .enablePendingToolRecovery(false)
                .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
                .maxIters(properties.getMaxIterations())
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(properties.getToolTimeoutSeconds()))
                        .maxAttempts(properties.getMaxToolAttempts())
                        .build());

        // 配置上下文压缩
        CompressConfig compressConfig = properties.getCompress();
        builder
                .compaction(CompactionConfig.builder()
                        .model(ModelFacade.getModel(compressConfig.getCompressModel()))
                        .triggerTokens(compressConfig.getTriggerTokens())
                        .triggerMessages(compressConfig.getTriggerMessages())
                        .keepMessages(compressConfig.getKeepMessages())
                        .keepTokens(compressConfig.getKeepTokens())
                        .flushBeforeCompact(compressConfig.isFlushBeforeCompact())
                        .offloadBeforeCompact(compressConfig.isOffloadBeforeCompact())
                        .build());

        // 长期记忆配置
        builder.
                memory(MemoryConfig.builder()
                        .flushPrompt(PromptLoader.loadRequired("prompt/agent_flush_memory.md"))
                        .flushTrigger(MemoryConfig.FlushTrigger
                                .throttled(
                                        Duration.ofMinutes(properties.getMemoryFlushMinutes())))
                        .build());

        return builder;
    }

    /**
     * 信息与规划节点只开放读取，防止它们在计划推断中直接修改用户默认。
     */
    private void registerPreferences(Toolkit toolkit, boolean writable) {
        toolkit.registerTool(userPreferenceTools);
        if (!writable) {
            toolkit.removeTool(UserPreferenceTools.UPDATE);
        }
    }

    private String systemPrompt(String path) {
        // 组合静态业务规则；用户的实际偏好仍由查询工具读取，不在这里加载数据。
        return PromptLoader.loadRequired(path) + "\n\n" + PromptLoader.loadRequired("prompt/travel-preferences.md");
    }
}
