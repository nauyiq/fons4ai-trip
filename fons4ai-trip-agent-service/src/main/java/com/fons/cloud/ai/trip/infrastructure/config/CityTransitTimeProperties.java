package com.fons.cloud.ai.trip.infrastructure.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 城际交通衔接估算配置，与差旅费用政策独立。
 * @author hongqy
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trip.transit")
public class CityTransitTimeProperties {
    private List<String> tier1Cities = new ArrayList<>();
    private List<String> newTier1Cities = new ArrayList<>();
    private List<String> tier2Cities = new ArrayList<>();

    /**
     * 城市对到分钟数，方向无关。YAML 中文键需用方括号保留，如 "[北京-上海]"。
     * 时长包含通勤、候车候机及在途时间，配置优先于城市分层估算。
     */
    private Map<String, Integer> cityPairMinutes = new HashMap<>();
}
