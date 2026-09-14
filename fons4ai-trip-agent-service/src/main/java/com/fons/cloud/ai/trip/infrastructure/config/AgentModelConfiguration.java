package com.fons.cloud.ai.trip.infrastructure.config;

import com.fons.cloud.ai.trip.agent.ModelFacade;
import com.fons.cloud.ai.trip.common.constants.ModelType;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 模型配置类, 针对不同的Agent使用不同的模型
 * @author hongqy
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties({ModelConfigProperties.class})
public class AgentModelConfiguration {
    private final ModelConfigProperties modelConfigProperties;

    @Bean(name = ModelFacade.FAST_MODEL)
    public Model fastModel() {
        ModelConfigProperties.ModelConfig config = validModelConfig(ModelType.FAST_MODEL);
        return OpenAIChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .stream(true)
                .
                .build();
    }


    private ModelConfigProperties.ModelConfig validModelConfig(ModelType type) {
        Map<ModelType, ModelConfigProperties.ModelConfig> models = modelConfigProperties.getModels();
        if (MapUtils.isEmpty(models) || !models.containsKey(type)) {
            throw SystemIntervalException.of("未找到TRAVEL-AGENT模型配置, 请检查");
        }
        ModelConfigProperties.ModelConfig config = models.get(type);
        if (config == null || StringUtils.isAnyBlank(config.getApiKey(), config.getBaseUrl(), config.getModelName())) {
            throw SystemIntervalException.of("模型配置不合法, 请检查, modelType:" + type);
        }
        return config;
    }

}
