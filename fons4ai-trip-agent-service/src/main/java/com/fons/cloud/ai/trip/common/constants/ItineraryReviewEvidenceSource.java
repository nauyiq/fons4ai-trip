package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核证据来源，避免仅通过自然语言描述无法追溯审核依据。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryReviewEvidenceSource {

    PLAN_RESULT("行程规划结果"),
    TRAVEL_ORDER("差旅申请单"),
    TRAVEL_POLICY("差旅政策"),
    WEATHER("天气数据"),
    DESTINATION_NEWS("目的地资讯"),
    USER_PREFERENCE("用户偏好"),
    REVIEW_RULE("审核规则");

    private final String label;
}
