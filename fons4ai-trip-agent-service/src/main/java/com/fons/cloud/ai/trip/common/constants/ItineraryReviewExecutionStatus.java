package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 整次行程审核的执行完整性，不表示方案本身是否可以采用。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryReviewExecutionStatus {

    COMPLETE("审核完整执行"),
    PARTIAL("部分审核维度未完成"),
    FAILED("审核未能形成有效结果");

    private final String label;
}
