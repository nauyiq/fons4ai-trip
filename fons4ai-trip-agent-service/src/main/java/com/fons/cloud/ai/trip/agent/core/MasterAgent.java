package com.fons.cloud.ai.trip.agent.core;

import com.fons.cloud.ai.agent.api.Agent;
import com.fons.cloud.ai.agent.api.AgentType;
import com.fons.cloud.ai.agent.core.AgentScopeHarnessAgent;
import com.fons.cloud.ai.agent.core.AgentTaskManager;
import com.fons.cloud.ai.agent.infrastructure.middleware.ActiveAgentPersistenceMiddleware;
import com.fons.cloud.ai.agent.infrastructure.middleware.FonsAgentTraceMiddleware;
import com.fons.cloud.ai.trip.agent.model.BusinessAgent;
import com.fons.cloud.ai.trip.agent.model.CompressConfig;
import com.fons.cloud.ai.trip.agent.model.MasterAgentProperties;
import com.fons.cloud.ai.trip.agent.tool.*;
import com.fons.cloud.ai.trip.infrastructure.client.ModelFacade;
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
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * 主Agent，负责Agent的路由/多意图识别/结果整合等
 *
 * @author hongqy
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MasterAgentProperties.class)
public class MasterAgent {
    private final MasterAgentProperties properties;
    private final DistributedStore distributedStore;
    private final AgentTaskManager agentTaskManager;

    // == middleware
    // 这里表示启用框架层提供的可观测性，配置文件里面一定要有sys.agent.observability的配置
    private final FonsAgentTraceMiddleware fonsAgentTraceMiddleware;
    // 为每轮请求提供可信日期 父子Agent公用
    private final TripTimeContextMiddleware tripTimeContextMiddleware;
    // 持久化当前会话顶层Agent身份的中间件
    private final ActiveAgentPersistenceMiddleware activeAgentPersistenceMiddleware;

    // == 业务工具清单
    private final BookingReadTools bookingReadTools;
    private final TravelOrderReadTools travelOrderReadTools;
    private final TravelOrderWriteTools travelOrderWriteTools;
    private final TravelOrderConflictTools travelOrderConflictTools;
    private final UserInfoReadTools userInfoReadTools;
    private final UserInfoWriteTools userInfoWriteTools;

    @Bean(name = "itineraryManageAgent")
    public HarnessAgent itineraryManageAgent() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(bookingReadTools);
        toolkit.registerTool(travelOrderReadTools);
        toolkit.registerTool(travelOrderWriteTools);
        toolkit.registerTool(travelOrderConflictTools);
        toolkit.registerTool(userInfoReadTools);
        toolkit.registerTool(userInfoWriteTools);

        return commonBuilder()
                .name(BusinessAgent.ITINERARY_MANAGE_AGENT.getAgentName())
                .description(BusinessAgent.ITINERARY_MANAGE_AGENT.getDescription())
                .sysPrompt(PromptLoader.loadRequired("prompt/itinerary-manage-agent-system.md"))
                .toolkit(toolkit)
                .disableSubagents()
                .build();
    }

    @Bean(name = "masterAgent")
    public Agent masterAgent(HarnessAgent itineraryManageAgent) {
        HarnessAgent.Builder masterBuilder = commonBuilder()
                .name(BusinessAgent.MASTER_AGENT.getAgentName())
                .sysPrompt(PromptLoader.loadRequired("prompt/master_agent_sys_prompt.md"))
                .middleware(activeAgentPersistenceMiddleware);

        // 配置行程管理子Agent
        masterBuilder.subagentFactory(BusinessAgent.ITINERARY_MANAGE_AGENT.getAgentName(), BusinessAgent.ITINERARY_MANAGE_AGENT.getDescription(),
                name -> itineraryManageAgent);

        // 采用fons4ai契约的agent实例
        return AgentScopeHarnessAgent.builder()
                .agentName(BusinessAgent.MASTER_AGENT.getAgentName())
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
                        .flushPrompt(PromptLoader.loadRequired("prompt/master_agent_flush_memory.md"))
                        .flushTrigger(MemoryConfig.FlushTrigger
                                .throttled(
                                        Duration.ofMinutes(properties.getMemoryFlushMinutes())))
                        .build());

        return builder;
    }


}
