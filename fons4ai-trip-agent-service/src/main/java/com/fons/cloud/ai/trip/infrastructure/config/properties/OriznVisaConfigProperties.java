package com.fons.cloud.ai.trip.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * @author hongqy
 */
@Getter
@Setter
@Component
public class OriznVisaConfigProperties {

    /**
     * 访问密钥
     */
    private String apiKey;

    /**
     * 访问路径
     */
    private String baseUrl;

    /**
     * HTTP连接建立的等待时间。
     */
    private Duration connectTimeout = Duration.ofSeconds(15);

    /**
     * HTTP读取等待时间，不包含连接建立阶段。
     */
    private Duration readTimeout = Duration.ofSeconds(60);

}
