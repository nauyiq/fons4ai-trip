package com.fons.cloud.ai.trip.infrastructure.config;

import com.fons.cloud.ai.trip.infrastructure.config.properties.TripTimeContextProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 统一模型日期上下文与差旅时间校验工具的业务时区。
 * @author hongqy
 */
@Configuration
@EnableConfigurationProperties(TripTimeContextProperties.class)
public class TripTimeContextConfiguration {

    public static final String TRIP_AGENT_CLOCK = "tripAgentClock";

    @Bean(name = TRIP_AGENT_CLOCK)
    public Clock tripAgentClock(TripTimeContextProperties properties) {
        String zoneId = properties.getZoneId();
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("trip.agent.time-context.zone-id must not be blank");
        }
        return Clock.system(ZoneId.of(zoneId.trim()));
    }
}
