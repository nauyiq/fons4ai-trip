package com.fons.cloud.ai.trip.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 差旅 Agent 的业务时间配置，不依赖服务器默认时区。
 * @author Administrator
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "trip.agent.time-context")
public class TripTimeContextProperties {

    /**
     * 业务时区，非法或空配置在创建业务 Clock 时阻止启动。
     */
    private String zoneId = "Asia/Shanghai";
}
