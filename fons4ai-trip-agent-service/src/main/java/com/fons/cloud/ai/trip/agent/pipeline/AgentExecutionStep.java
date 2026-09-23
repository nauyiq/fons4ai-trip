package com.fons.cloud.ai.trip.agent.pipeline;

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
     * 语义意图识别， 通过L0, L1, L2级别的文本匹配 判断用户意图
     */
    SEMANTICS_RECOGNITION_STEP,

    /**
     * 问题重写步骤， 直接调用LLM发起问题改写
     */
    QUERY_WRITING_STEP,

    /**
     * 意图识别步骤
     */
    INTENT_RECOGNITION_STEP,

    /**
     * 最终执行任务步骤
     */
    EXECUTE_TASK_STEP,

    /**
     * 结束步骤， 不会有任何实现
     */
    END,

    ;




}
