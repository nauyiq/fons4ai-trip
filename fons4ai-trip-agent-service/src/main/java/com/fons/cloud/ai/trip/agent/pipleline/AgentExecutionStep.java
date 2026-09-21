package com.fons.cloud.ai.trip.agent.pipleline;

import com.fons.cloud.ai.trip.agent.core.TripAgent;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Agent执行管道步骤
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum AgentExecutionStep {

    /**
     * 语义识别， 通过L0, L1, L2级别的文本匹配 判断用户意图
     */
    SEMANTICS_RECOGNITION_STEP(null),

    /**
     * 问题重写步骤， 直接调用LLM发起问题改写
     */
    LLM_QUERY_WRITING_STEP(TripAgent.QUERY_REWRITE_AGENT.getAgentName()),

    /**
     * LLM意图识别步骤
     */
    LLM_INTENT_RECOGNITION_STEP(TripAgent.INTENT_RECOGNITION_AGENT.getAgentName()),

    /**
     * 最终执行任务步骤
     */
    EXECUTE_TASK_STEP(TripAgent.MASTER_AGENT.getAgentName()),


    ;

    private final String agentName;

    public static AgentExecutionStep getAgentExecutionStep(String agentName) {
        for (AgentExecutionStep step : values()) {
            if (step.getAgentName() != null && step.getAgentName().equals(agentName)) {
                return step;
            }
        }
        return null;
    }



}
