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
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolkitConfig;
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

import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
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
    private final MasterAgentConfigProperties properties;
    private final DistributedStore distributedStore;
    private final AgentTaskManager agentTaskManager;

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
        // 子AGENT构建
        HarnessAgent itineraryManageAgent = childBuilder(toolkit)
                .name(TripAgent.ITINERARY_MANAGE_AGENT.getAgentName())
                .description(TripAgent.ITINERARY_MANAGE_AGENT.getDescription())
                .sysPrompt(PromptLoader.loadRequired("prompt/itinerary-manage-agent-system.md"))
                .middleware(businessToolGuard(Set.of(), bookingReadTools, travelOrderReadTools,
                        travelOrderWriteTools, travelOrderConflictTools, userInfoReadTools, userInfoWriteTools))
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
        if (weatherMcp.getMcpClient() != null) {
            toolkit.registration().mcpClient(weatherMcp.getMcpClient())
                    .enableTools(weatherMcp.getEnabledTools())
                    .apply();
        }

        // 子AGENT构建
        HarnessAgent itineraryPlanAgent = childBuilder(toolkit)
                .name(TripAgent.ITINERARY_PLAN_AGENT.getAgentName())
                .description(TripAgent.ITINERARY_PLAN_AGENT.getDescription())
                .sysPrompt(PromptLoader.loadRequired("prompt/itinerary-plan-agent-system.md"))
                .enableTaskList(true)
                // 天气和目的地资讯属于可选补充，其失败不能把已完成的核心规划永久阻塞。
                .middleware(businessToolGuard(Set.copyOf(DestinationLiveTools.TOOLS), bookingReadTools,
                        userInfoReadTools, userInfoWriteTools, travelOrderReadTools, destinationLiveTools,
                        policyTools, itinerarySearchTools, itineraryPlannerTools, itineraryPlanReadTools))
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
        if (weatherMcp.getMcpClient() != null) {
            toolkit.registration().mcpClient(weatherMcp.getMcpClient())
                    .enableTools(weatherMcp.getEnabledTools())
                    .apply();
        }
        HarnessAgent infoAgent = childBuilder(toolkit)
                .name(TripAgent.INFO_AGENT.getAgentName())
                .description(TripAgent.INFO_AGENT.getDescription())
                .sysPrompt(PromptLoader.loadRequired("prompt/info-agent-system.md"))
                .middleware(businessToolGuard(Set.of(), policyTools, destinationLiveTools, visaTools))
                .disableSubagents()
                .build();
        log.info("[MasterAgent] 信息查询子Agent创建成功...");
        return infoAgent;
    }

    @Bean(name = "masterAgent")
    public Agent masterAgent(HarnessAgent itineraryManageAgent,
                             HarnessAgent itineraryPlanAgent,
                             HarnessAgent infoAgent) {
        HarnessAgent.Builder masterBuilder = commonBuilder()
                .name(TripAgent.MASTER_AGENT.getAgentName())
                .sysPrompt(PromptLoader.loadRequired("prompt/master_agent_sys_prompt.md"))
                .middleware(analysisAgentMiddleware)
                // 即使模型一次返回多个调用，也按顺序执行；子Agent仍使用各自的Toolkit。
                .toolkit(new Toolkit(ToolkitConfig.builder().parallel(false).build()));

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
        var askTools = properties.getApprovalRequiredTools().stream()
                .filter(name -> toolkit.getTool(name) != null)
                .sorted().toList();
        if (!askTools.isEmpty()) {
            PermissionContextState.Builder permissions = PermissionContextState.builder();
            // 每个子Agent只接收自己实际拥有的工具规则；委派工具不进入业务审批清单。
            for (String name : askTools) {
                permissions.addAskRule(name, new PermissionRule(name, null, PermissionBehavior.ASK,
                        "业务配置要求执行前人工确认"));
            }
            builder.permissionContext(permissions.build());
        }
        return builder;
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


}
