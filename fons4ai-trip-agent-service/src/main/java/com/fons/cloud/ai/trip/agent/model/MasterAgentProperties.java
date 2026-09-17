package com.fons.cloud.ai.trip.agent.model;

import com.fons.cloud.ai.trip.common.constants.ModelType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @author hongqy
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "trip.agent.master")
public class MasterAgentProperties {

    /**
     * 工作空间
     */
    private String workspace = "/trip/agentscope";

    /**
     * 执行任务的主模型类型
     */
    private ModelType mainModel = ModelType.STRONG_MODEL;

    /**
     * 主 Agent 工具执行超时， 默认15分钟
     */
    private Integer toolTimeoutSeconds = 15 * 60;

    /**
     * 主 Agent 最大推理轮次， 默认15
     */
    private Integer maxIterations = 15;

    /**
     * 记忆刷新时间， 默认10分钟
     */
    private Integer memoryFlushMinutes = 10;

    /**
     * 最大工具尝试次数，默认3次
     */
    private Integer maxToolAttempts = 3;

    /**
     * 上下文压缩配置
     */
    private CompressConfig compress = new CompressConfig();
}
