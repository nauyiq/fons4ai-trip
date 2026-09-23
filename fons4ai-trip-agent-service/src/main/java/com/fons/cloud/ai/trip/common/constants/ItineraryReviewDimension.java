package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Trip 行程规划的业务审核维度。
 * 数据结构损坏、审核器异常等属于执行状态，不作为业务审核维度。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryReviewDimension {

    TRAVEL_ORDER_CONSISTENCY("差旅单一致性"),
    POLICY_COMPLIANCE("差旅政策合规"),
    EXECUTION_FEASIBILITY("方案执行可行性"),
    EXPERIENCE_AND_PREFERENCE("差旅体验与偏好匹配"),
    RESILIENCE("行程韧性");

    private final String label;
}
