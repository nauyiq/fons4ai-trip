package com.fons.cloud.ai.trip.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一管理Trip服务用到的MCP配置
 * @author hongqy
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trip.mcp")
public class TripMcpConfigProperties {

    /**
     * 天气MCP查询配置
     */
    private WeatherMcpConfig weather = new WeatherMcpConfig();


    @Getter
    @Setter
    public static class WeatherMcpConfig {

        /**
         * 天气查询服务的Streamable HTTP 端点
         */
        private String endpoint;

        /**
         * 工具请求超时时间 默认30s
         */
        private Integer requestTimeoutSeconds = 30;

        /**
         *  MCP 初始化握手超时（秒)
         */
        private Integer initialTimeoutSeconds = 15;
    }


}
