package com.fons.cloud.ai.trip.infrastructure.util;

import com.fons.cloud.ai.trip.infrastructure.config.CityTransitTimeProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 城际交通时长估算工具服务，使用城市对配置及城市分层规则，不查询真实班次。
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CityTransitTimeTools {
    private static final int DEFAULT_MINUTES = 240;
    private static final int TIER_OTHER_MINUTES = 360;
    private static final int TIER2_MINUTES = 300;
    private static final int TIER1_MINUTES = 270;

    private final CityTransitTimeProperties properties;
    private Map<String, Integer> cityPairMinutes = Map.of();

    @PostConstruct
    public void init() {
        Map<String, Integer> expanded = new HashMap<>();
        Map<String, Integer> configured = properties.getCityPairMinutes();
        if (configured != null) {
            for (Map.Entry<String, Integer> entry : configured.entrySet()) {
                String[] cities = StringUtils.defaultString(entry.getKey()).split("-", -1);
                if (cities.length != 2 || StringUtils.isBlank(cities[0]) || StringUtils.isBlank(cities[1])
                        || entry.getValue() == null || entry.getValue() <= 0) {
                    log.warn("[CityTransitTime] 忽略无效城市对配置，key={}, minutes={}", entry.getKey(), entry.getValue());
                    continue;
                }
                String from = normalize(cities[0]);
                String to = normalize(cities[1]);
                expanded.put(from + "-" + to, entry.getValue());
                expanded.put(to + "-" + from, entry.getValue());
            }
        }
        cityPairMinutes = Map.copyOf(expanded);
        log.info("[CityTransitTime] 已加载城市对估算配置 {} 条（含正反方向）", cityPairMinutes.size());
    }

    /**
     * 同城为 0 分钟，跨城优先使用显式配置，再按城市分层估算。
     */
    public int estimateMinutes(String fromCity, String toCity) {
        if (StringUtils.isBlank(fromCity) || StringUtils.isBlank(toCity)) {
            log.warn("[CityTransitTime] 城市缺失，from={}, to={}", fromCity, toCity);
            return DEFAULT_MINUTES;
        }
        String from = normalize(fromCity);
        String to = normalize(toCity);
        if (from.equals(to)) {
            return 0;
        }
        Integer explicit = cityPairMinutes.get(from + "-" + to);
        if (explicit != null) {
            return explicit;
        }
        if (!isKnownCity(from) || !isKnownCity(to)) {
            return TIER_OTHER_MINUTES;
        }
        if (contains(properties.getTier2Cities(), from) || contains(properties.getTier2Cities(), to)) {
            return TIER2_MINUTES;
        }
        return TIER1_MINUTES;
    }

    private boolean isKnownCity(String city) {
        return contains(properties.getTier1Cities(), city)
                || contains(properties.getNewTier1Cities(), city)
                || contains(properties.getTier2Cities(), city);
    }

    private boolean contains(List<String> cities, String city) {
        return cities != null && cities.stream().anyMatch(value -> city.equals(normalize(value)));
    }

    private String normalize(String city) {
        String value = StringUtils.trimToEmpty(city);
        if (value.endsWith("市") && value.length() > 1) {
            value = value.substring(0, value.length() - 1);
        }
        return value.toLowerCase(Locale.ROOT);
    }
}
