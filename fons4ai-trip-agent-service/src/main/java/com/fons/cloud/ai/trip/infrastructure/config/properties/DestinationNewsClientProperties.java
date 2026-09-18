package com.fons.cloud.ai.trip.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * NewsData.io资讯客户端配置，使用服务级凭据，不读取用户途牛凭据。
 *
 * @author hongqy
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trip.destination-news")
public class DestinationNewsClientProperties {

    /** 服务地址，不接受模型提供，查询使用/api/1/news端点。 */
    private String baseUrl = "https://newsdata.io";

    /** 服务级API Key，空值表示资讯查询暂未启用，不阻止应用启动。 */
    private String apiKey;

    /** 供应商语言过滤参数，默认查询中文资讯。 */
    private String language = "zh";

    /** 单页条数，默认5；供应商协议范围1至50，实际最大值还取决于账号套餐。 */
    private int pageSize = 5;

    /** 查询词长度限制，默认100个Unicode字符，可根据供应商账号套餐调整。 */
    private int maxQueryLength = 100;

    /** HTTP连接建立的等待时间。 */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /** HTTP读取等待时间，不包含连接建立阶段。 */
    private Duration readTimeout = Duration.ofSeconds(10);
}
