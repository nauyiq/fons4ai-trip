package com.fons.cloud.ai.trip.infrastructure.config;

import com.fons.cloud.ai.trip.common.constants.ModelType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/**
 * @author hongqy
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "trip.agent")
public class ModelConfigProperties {

    private Map<ModelType, ModelConfig> models;

    @Getter
    @Setter
    public static class ModelConfig {
        private String apiKey;
        private String baseUrl;
        private String modelName;

    }

}
