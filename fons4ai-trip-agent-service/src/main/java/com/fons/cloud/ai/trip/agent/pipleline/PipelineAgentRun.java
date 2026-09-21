package com.fons.cloud.ai.trip.agent.pipleline;

import com.fons.cloud.ai.agent.api.AgentRun;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 工作流内的AgentRun结果 主要将工作流中不同步骤的AGENT输出嫁接到一起。
 * 核心任务AgentRun 还是基于MasterAgent
 * @author hongqy
 */
@RequiredArgsConstructor
public class PipelineAgentRun implements AgentRun {



    @Override
    public String runId() {
        return "";
    }

    @Override
    public AgentRunState state() {
        return null;
    }

    @Override
    public Flux<String> events() {
        return null;
    }

    @Override
    public Mono<AgentRunResult> completion() {
        return null;
    }

    @Override
    public boolean cancel() {
        return false;
    }
}
