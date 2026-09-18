package com.fons.cloud.ai.trip.agent.mcp;

import com.fons.cloud.ai.trip.infrastructure.config.properties.TripMcpConfigProperties;
import io.agentscope.core.tool.mcp.McpClientBuilder;
import io.agentscope.core.tool.mcp.McpClientWrapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 天气查询 MCP 客户端配置。
 * <p>天气查询服务（Streamable HTTP 传输）。该 MCP 作为 wttr.in 的补充：wttr.in 仅支持今日起 3 天内，超出范围时由本 MCP 接管。
 * @author hongqy
 */
@Slf4j
@Component
public class WeatherMcp extends BaseMcp {
    public WeatherMcp(TripMcpConfigProperties properties) {
        super(properties);
    }

    private volatile McpClientWrapper wrapper = null;

    @PostConstruct
    public void init() {
        log.info("[WeatherMcp] 初始化 天气 MCP 客户端 (Streamable HTTP)...");
        TripMcpConfigProperties.WeatherMcpConfig weather = properties.getWeather();
        if (weather == null || StringUtils.isBlank(weather.getEndpoint())) {
            log.warn("[WeatherMcp] 缺失天气MCP配置信息...");
            return;
        }
        try {
            McpClientWrapper client = McpClientBuilder.create("weather-mcp")
                    // Streamable HTTP 传输
                    .streamableHttpTransport(weather.getEndpoint())
                    // 工具调用请求超时
                    .timeout(Duration.ofSeconds(weather.getRequestTimeoutSeconds()))
                    // MCP 初始化握手超时
                    .initializationTimeout(Duration.ofSeconds(weather.getInitialTimeoutSeconds()))
                    .buildAsync()
                    .block();
            log.info("[WeatherMcp] 天气 MCP 客户端初始化成功");
            this.wrapper = client;
        } catch (Exception e) {
            log.warn("[WeatherMcp] 天气 MCP 客户端初始化失败，超过 3 天的天气查询将不可用。原因：{}", e.getMessage());
        }
    }

    @Override
    protected McpClientWrapper getMcpClient() {
        return wrapper;
    }
}
