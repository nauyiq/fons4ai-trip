package com.fons.cloud.ai.trip.agent.core;

import com.fons.cloud.ai.agent.api.Agent;
import com.fons.cloud.ai.trip.agent.model.BusinessAgent;
import com.fons.cloud.ai.trip.agent.model.CompressConfig;
import com.fons.cloud.ai.trip.agent.model.MasterAgentProperties;
import com.fons.cloud.ai.trip.infrastructure.client.ModelFacade;
import io.agentscope.core.model.ExecutionConfig;
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
import org.springframework.context.annotation.Scope;

import java.nio.file.Paths;
import java.time.Duration;

/**
 * 主Agent，负责Agent的路由/多意图识别/结果整合等
 * @author hongqy
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(MasterAgentProperties.class)
public class MasterAgent {

    private final MasterAgentProperties properties;
    private final DistributedStore distributedStore;


    @Scope("prototype")
    @Bean(name = "masterAgent")
    public Agent masterAgent() {
        HarnessAgent.Builder builder = HarnessAgent.builder();
        // 基础配置
        builder
                .name(BusinessAgent.MASTER_AGENT.getAgentName())
                .model(ModelFacade.getModel(properties.getMainModel()))
                .workspace(Paths.get(properties.getWorkspace()))
                .distributedStore(distributedStore)
                .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
                .maxIters(properties.getMaxIterations())
                .toolExecutionConfig(ExecutionConfig.builder()
                        .timeout(Duration.ofSeconds(properties.getToolTimeoutSeconds()))
                        .maxAttempts(3)
                        .build());

        // 配置上下文压缩
        CompressConfig compressConfig = properties.getCompress();
        builder
                .compaction(CompactionConfig.builder()
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
                        .flushTrigger(MemoryConfig.FlushTrigger.throttled(
                                Duration.ofMinutes(properties.getMemoryFlushMinutes())))
                        .build());

        // 工具配置


        // 子Agent配置

        return null;
    }


}
