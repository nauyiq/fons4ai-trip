package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 候选报价的计价口径，避免将酒店起价直接作为整段住宿费用。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum SearchPriceBasis {

    PER_PERSON_ONE_WAY("单人单程"),
    PER_ROOM_PER_NIGHT("单间每晚"),
    PER_ROOM_STAY("单间整个入住期间"),
    UNKNOWN("供应商未明确计价口径");

    /**
     * 计价口径说明。
     */
    private final String label;
}
