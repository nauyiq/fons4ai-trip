package com.fons.cloud.ai.trip.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 统一管理Trip服务用到的MCP配置
 *
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

    /**
     * 途牛搜索配置，企业统一管理服务凭据，不使用员工个人API Key。
     */
    private TuniuMcpConfig tuniu = new TuniuMcpConfig();


    @Getter
    @Setter
    public static class OriznVisaConfig {

    }


    @Getter
    @Setter
    public static class TuniuMcpConfig {

        /**
         * 企业服务级API Key，通过环境变量或配置中心注入，不提供给LLM。空值时搜索不可用。
         */
        private String apiKey;

        /**
         * 国内机票搜索的Streamable HTTP端点。
         */
        private String flightEndpoint = "https://openapi.tuniu.cn/mcp/flight";

        /**
         * 火车票搜索的Streamable HTTP端点。
         */
        private String trainEndpoint = "https://openapi.tuniu.cn/mcp/train";

        /**
         * 酒店搜索的Streamable HTTP端点。
         */
        private String hotelEndpoint = "https://openapi.tuniu.cn/mcp/hotel";

        /**
         * 供应商工具名称，可随途牛协议调整。
         */
        private String flightSearchTool = "searchLowestPriceFlight";
        private String trainSearchTool = "searchLowestPriceTrain";
        private String hotelSearchTool = "tuniuHotelSearch";
        private String hotelDetailTool = "tuniuHotelDetail";

        /**
         * HTTP连接建立超时（秒）。
         */
        private Integer connectTimeoutSeconds = 3;

        /**
         * MCP工具请求超时（秒），不涉及用户补充信息的等待时间。
         */
        private Integer requestTimeoutSeconds = 30;

        /**
         * MCP初始化握手超时（秒）。
         */
        private Integer initialTimeoutSeconds = 15;
    }


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
         * MCP 初始化握手超时（秒)
         */
        private Integer initialTimeoutSeconds = 15;
    }


}
