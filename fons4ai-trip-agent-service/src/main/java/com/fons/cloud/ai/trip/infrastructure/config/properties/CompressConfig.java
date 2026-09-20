package com.fons.cloud.ai.trip.infrastructure.config.properties;

import com.fons.cloud.ai.trip.common.constants.ModelType;
import lombok.Getter;
import lombok.Setter;

/**
 * 上下文压缩配置类
 * @author hongqy
 */
@Getter
@Setter
public class CompressConfig {

    /**
     * 上下文压缩的模型类型
     */
    private ModelType compressModel = ModelType.STABLE_MODEL;

    /**
     * 多少条消息触发上下文压缩， 默认0
     */
    private Integer triggerMessages = 0;

    /**
     * 多少token触发上下文压缩， 默认100K
     */
    private Integer triggerTokens = 100_000;

    /**
     * 压缩时保留最近多少条消息原文, 默认10
     */
    private Integer keepMessages = 10;

    /**
     * 压缩时保留多少token，默认0
     */
    private Integer keepTokens = 0;

    /**
     * 是否在压缩上下文之前提取长期记忆
     */
    private boolean flushBeforeCompact = true;

    /**
     * 压缩前保存完整原始消息
     */
    private boolean offloadBeforeCompact = true;
}
