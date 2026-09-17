package com.fons.cloud.ai.trip.infrastructure.config.properties;

import com.fons.cloud.ai.trip.common.constants.ModelType;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * @author hongqy
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "trip.model")
public class ModelConfigProperties {

    private Map<ModelType, ModelConfig> configs = new HashMap<>();

    @Getter
    @Setter
    public static class ModelConfig {
        private String apiKey;
        private String baseUrl;
        private String modelName;
    }

}
