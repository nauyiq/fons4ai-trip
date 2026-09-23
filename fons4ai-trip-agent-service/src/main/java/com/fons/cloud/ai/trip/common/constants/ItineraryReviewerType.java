package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核维度的执行方式，用于追溯结论来自确定性规则、LLM评估或二者组合。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryReviewerType {

    DETERMINISTIC_RULE("Java确定性规则"),
    LLM_ASSESSOR("LLM主观评估"),
    HYBRID("确定性规则与LLM组合评估");

    private final String label;
}
