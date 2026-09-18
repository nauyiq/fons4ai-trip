package com.fons.cloud.ai.trip.infrastructure.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 差旅费用政策城市分级，与城际交通时长估算配置独立。
 *
 * @author hongqy
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trip.policy")
public class TravelPolicyProperties {

    private List<String> tier1Cities = new ArrayList<>();
    private List<String> newTier1Cities = new ArrayList<>();
    private List<String> tier2Cities = new ArrayList<>();
}
