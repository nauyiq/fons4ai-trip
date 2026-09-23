package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 整次审核完成后建议进入的下一业务阶段。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryReviewNextAction {

    PROCEED("可以向用户展示并选择方案"),
    REPLAN("需要修复后重新规划"),
    RETRY_REVIEW("审核不完整，需要重新审核"),
    REQUEST_USER_INPUT("需要用户补充信息或作出选择"),
    REQUIRE_MANUAL_APPROVAL("需要人工审批或豁免"),
    STOP_NO_FEASIBLE_PROPOSAL("没有可继续推进的方案");

    private final String label;
}
