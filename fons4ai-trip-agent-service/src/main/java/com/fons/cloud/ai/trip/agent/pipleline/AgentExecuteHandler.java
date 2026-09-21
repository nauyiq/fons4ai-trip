package com.fons.cloud.ai.trip.agent.pipleline;

import com.fons.cloud.common.pipeline.PipelineHandler;

/**
 * 一次Agent管道处理器
 * @author hongqy
 */
public interface AgentExecuteHandler extends PipelineHandler<AgentExecuteContext> {

    /**
     * 当前处理器对应步骤
     * @return
     */
    AgentExecutionStep currentStep();

    /**
     * 正常工作流执行时 应该流转的步骤
     * @return
     */
    AgentExecutionStep nextStep();

}
