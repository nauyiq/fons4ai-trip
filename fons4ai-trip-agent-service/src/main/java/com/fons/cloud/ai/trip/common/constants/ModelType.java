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
     * 快速模型，用于简单任务；思考开关由对应模型配置决定。
     */
    FAST_MODEL("fastModel"),

    /**
     * 高效模型，用于复杂任务；思考开关由对应模型配置决定。
     */
    STRONG_MODEL("strongModel"),

    /**
     * 推理模型角色；保留既有枚举名，具体思考参数由对应模型配置决定。
     */
    STRONG_THING_MODEL("strongThinkModel"),

    /**
     * 稳定的模型
     */
    STABLE_MODEL("stableModel");


    private final String beanName;
}
