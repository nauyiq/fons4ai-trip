package com.fons.cloud.ai.trip.infrastructure.config.properties;

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
public class MasterAgentConfigProperties {

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

    /** Master的委派技术约束；不影响子Agent内部的业务工具或计划看板。 */
    private DelegationConfig delegation = new DelegationConfig();

    /** 仅配置当前委派模式，不提前引入重试、熔断或模型降级配置。 */
    @Getter
    @Setter
    public static class DelegationConfig {

        /** 首次委派必须提供非空task，默认开启。 */
        private boolean requireInitialTask = true;

        /** 同步取得子Agent结果，不提升为后台任务，默认开启。 */
        private boolean forceSync = true;

        /** 同步等待上限，单位秒，SDK允许1到600秒；独立于普通工具执行超时。 */
        private int syncTimeoutSeconds = 600;
    }
}
