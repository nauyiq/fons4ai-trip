package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.DimensionReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewIssue;

import java.util.List;

/**
 * 单个审核维度的完整输出，供审核应用服务汇总。
 * 维度审核器只生成本维度结论和问题，不直接选择最终推荐方案。
 *
 * @param dimensionReview 维度执行状态及业务结论
 * @param issues 本维度发现的问题；没有问题时为空列表
 * @author hongqy
 */
public record ItineraryDimensionReviewResult(DimensionReview dimensionReview,
                                             List<ReviewIssue> issues) {

    public ItineraryDimensionReviewResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
