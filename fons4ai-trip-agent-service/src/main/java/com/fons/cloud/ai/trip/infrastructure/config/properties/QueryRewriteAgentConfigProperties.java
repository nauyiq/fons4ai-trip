package com.fons.cloud.ai.trip.infrastructure.config.properties;

import com.fons.cloud.ai.trip.common.constants.ModelType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 问题改写Agent配置
 * @author hongqy
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trip.agent.query-rewrite")
public class QueryRewriteAgentConfigProperties {

    /**
     * 负责问题改写的模型
     */
    private ModelType modelType;

    /**
     * 系统提示词, 如果为空则默认使用项目内置的系统提示词
     */
    private String systemPrompt;
}
