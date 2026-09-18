package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 差旅政策使用的城市等级。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum CityTier {

    TIER_1("一线"),
    NEW_TIER_1("新一线"),
    TIER_2("二线"),
    OTHER("其他");

    /** 政策规则表和返回结果使用的中文名称。 */
    private final String label;

    /** 规则表只区分一线、新一线、其他，二线使用其他档政策。 */
    public CityTier getPolicyTier() {
        return this == TIER_2 ? OTHER : this;
    }
}
