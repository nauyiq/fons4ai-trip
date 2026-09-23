package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 行程审核业务结论，可用于整次审核、单个方案和已完成的审核维度。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryReviewVerdict {

    PASS("通过"),
    WARNING("存在风险提醒"),
    BLOCKED("存在阻断问题");

    private final String label;
}
