package com.fons.cloud.ai.trip.agent.core;

import com.fons.cloud.ai.agent.api.Agent;
import com.fons.cloud.ai.agent.api.AgentType;
import com.fons.cloud.ai.agent.core.AgentScopeHarnessAgent;
import com.fons.cloud.ai.agent.core.AgentTaskManager;
import com.fons.cloud.ai.agent.infrastructure.middleware.ActiveAgentPersistenceMiddleware;
import com.fons.cloud.ai.agent.infrastructure.middleware.FonsAgentTraceMiddleware;
import com.fons.cloud.ai.trip.agent.mcp.WeatherMcp;
import com.fons.cloud.ai.trip.infrastructure.config.properties.CompressConfig;
import com.fons.cloud.ai.trip.infrastructure.config.properties.MasterAgentConfigProperties;
import com.fons.cloud.ai.trip.agent.tool.*;
import com.fons.cloud.ai.trip.infrastructure.util.ModelFacade;
import com.fons.cloud.ai.trip.infrastructure.middleware.TripTimeContextMiddleware;
import com.fons.cloud.ai.trip.infrastructure.prompt.PromptLoader;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.tool.Toolkit;
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

/**
 * 主Agent，负责Agent的路由/多意图识别/结果整合等
 *
 * @author hongqy
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MasterAgentConfigProperties.class)
public class MasterAgent {
    private final MasterAgentConfigProperties properties;
    private final DistributedStore distributedStore;
    private final AgentTaskManager agentTaskManager;

    // == middleware ==
    // 这里表示启用框架层提供的可观测性，配置文件里面一定要有sys.agent.observability的配置
    private final FonsAgentTraceMiddleware fonsAgentTraceMiddleware;
    // 为每轮请求提供可信日期 父子Agent公用
    private final TripTimeContextMiddleware tripTimeContextMiddleware;
    // 持久化当前会话顶层Agent身份的中间件
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
    private final DestinationLiveTools destinationLiveTools;

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
        HarnessAgent itineraryManageAgent = commonBuilder()
                .name(TripAgent.ITINERARY_MANAGE_AGENT.getAgentName())
                .description(TripAgent.ITINERARY_MANAGE_AGENT.getDescription())
                .sysPrompt(PromptLoader.loadRequired("prompt/itinerary-manage-agent-system.md"))
                .toolkit(toolkit)
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

        // 子AGENT构建
        HarnessAgent itineraryPlanAgent = commonBuilder()
                .name(TripAgent.ITINERARY_PLAN_AGENT.getAgentName())
                .description(TripAgent.ITINERARY_PLAN_AGENT.getDescription())
                .enableTaskList(true)
                .toolkit(toolkit)
                .disableSubagents()
                .build();
        log.info("[MasterAgent] 行程规划子Agent创建成功...");
        return itineraryPlanAgent;
    }

    @Bean(name = "masterAgent")
    public Agent masterAgent(HarnessAgent itineraryManageAgent,
                             HarnessAgent itineraryPlanAgent) {
        HarnessAgent.Builder masterBuilder = commonBuilder()
                .name(TripAgent.MASTER_AGENT.getAgentName())
                .sysPrompt(PromptLoader.loadRequired("prompt/master_agent_sys_prompt.md"))
                .middleware(activeAgentPersistenceMiddleware);

        // 配置行程管理子Agent
        masterBuilder.subagentFactory(TripAgent.ITINERARY_MANAGE_AGENT.getAgentName(), TripAgent.ITINERARY_MANAGE_AGENT.getDescription(),
                name -> itineraryManageAgent);

        // 配置行程规划子Agent
        masterBuilder.subagentFactory(TripAgent.ITINERARY_PLAN_AGENT.getAgentName(), TripAgent.ITINERARY_PLAN_AGENT.getDescription(),
                name -> itineraryPlanAgent);

        // 采用fons4ai契约的agent实例
        return AgentScopeHarnessAgent.builder()
                .agentName(TripAgent.MASTER_AGENT.getAgentName())
                .agentType(AgentType.HARNESS)
                .agentTaskManager(agentTaskManager)
                .delegateBuilder(masterBuilder)
                .inputRequiredEnabled(true)
                .build();
    }

    private HarnessAgent.Builder commonBuilder() {
        HarnessAgent.Builder builder = HarnessAgent.builder()
                .middleware(tripTimeContextMiddleware)
                .middleware(fonsAgentTraceMiddleware)
                .model(ModelFacade.getModel(properties.getMainModel()))
                .workspace(Paths.get(properties.getWorkspace()))
                .distributedStore(distributedStore)
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
