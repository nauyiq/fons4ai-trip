package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewerType;

import java.util.List;

/**
 * 单个业务审核维度的执行状态与结论。
 *
 * @author hongqy
 */
public record ItineraryDimensionReview(ItineraryReviewDimension dimension,
                                       ItineraryReviewDimensionStatus status,
                                       ItineraryReviewerType reviewerType,
                                       String reviewerVersion,
                                       ItineraryReviewVerdict verdict,
                                       List<String> issueIds,
                                       String summary) {

    public ItineraryDimensionReview {
        issueIds = issueIds == null ? List.of() : List.copyOf(issueIds);
    }
}
