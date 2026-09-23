package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 单个审核维度的执行状态，用于区分业务结论与缺少证据、执行异常。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryReviewDimensionStatus {

    COMPLETE("维度审核完成"),
    PARTIAL("部分规则因证据不足未完成评估"),
    NOT_APPLICABLE("当前规划不适用该审核维度"),
    NOT_EVALUATED("缺少必要证据，未完成评估"),
    FAILED("维度审核执行失败");

    private final String label;
}
