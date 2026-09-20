package com.fons.cloud.ai.trip.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 天气HTTP客户端配置；超时仅用于限制外部请求，不涉及用户输入等待时间。
 *
 * @author hongqy
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trip.weather")
public class WeatherClientProperties {

    /**
     * wttr.in服务地址，城市作为URI模板参数编码，不接受模型提供服务地址。
     */
    private String baseUrl = "https://wttr.in";

    /**
     * 建立HTTP连接的最长等待时间。
     */
    private Duration connectTimeout = Duration.ofSeconds(15);

    /**
     * 单次HTTP请求的读取等待时间，不包含连接建立阶段。
     */
    private Duration readTimeout = Duration.ofSeconds(60);
}
