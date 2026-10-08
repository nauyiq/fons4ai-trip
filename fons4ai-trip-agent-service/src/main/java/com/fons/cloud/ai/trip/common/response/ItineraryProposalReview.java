package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;

import java.util.List;

/**
 * 单个代表方案的审核结论；审核不完整时不允许进入推荐候选。
 *
 * @author hongqy
 */
public record ItineraryProposalReview(String proposalId, ItineraryReviewVerdict verdict,
                                      boolean eligibleForRecommendation, List<String> issueIds) {

    public ItineraryProposalReview {
        issueIds = issueIds == null ? List.of() : List.copyOf(issueIds);
    }
}
