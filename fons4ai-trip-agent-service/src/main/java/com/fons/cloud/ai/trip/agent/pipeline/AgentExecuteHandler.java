package com.fons.cloud.ai.trip.agent.pipeline;

import com.fons.cloud.reactor.api.ReactiveTaskScope;
import reactor.core.publisher.Mono;

/**
 * 一次Agent管道处理器
 * @author hongqy
 */
public interface AgentExecuteHandler {

    /**
     * 步骤处理逻辑，  使用响应式规范
     * @param context
     * @param scope
     * @return
     */
    Mono<Void> handle(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope);

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
