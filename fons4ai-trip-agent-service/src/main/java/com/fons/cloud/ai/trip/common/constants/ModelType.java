package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ModelType {


    /**
     * 快速模型， 用于简单的任务
     */
    FAST_MODEL("fastModel"),

    /**
     * 不带思考的高效模型, 用于复杂任务推理等
     */
    STRONG_MODEL("strongModel"),

    /**
     * 带思考的高效模型, 用于复杂任务推理等
     */
    STRONG_THING_MODEL("strongThinkModel"),

    /**
     * 稳定的模型
     */
    STABLE_MODEL("stableModel");


    private final String beanName;
}
