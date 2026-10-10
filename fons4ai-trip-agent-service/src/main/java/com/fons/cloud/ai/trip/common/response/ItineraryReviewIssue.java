package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewIssueCode;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;

import java.util.List;

/**
 * 可定位、可追溯的审核问题；受影响方案为空时表示影响整次规划。
 *
 * @author hongqy
 */
public record ItineraryReviewIssue(String issueId,
                                   ItineraryReviewIssueCode code,
                                   ItineraryReviewDimension dimension,
                                   ItineraryReviewSeverity severity,
                                   boolean hardConstraint,
                                   boolean repairableByReplanning,
                                   List<String> affectedProposalIds,
                                   List<ItineraryReviewEvidence> evidence,
                                   String message) {

    public ItineraryReviewIssue {
        affectedProposalIds = affectedProposalIds == null ? List.of() : List.copyOf(affectedProposalIds);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }
}
