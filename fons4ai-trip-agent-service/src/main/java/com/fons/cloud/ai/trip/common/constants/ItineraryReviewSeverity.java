package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 单个审核问题的严重程度。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryReviewSeverity {

    ADVISORY("优化建议"),
    WARNING("风险提醒"),
    BLOCKING("阻断问题");

    private final String label;
}
