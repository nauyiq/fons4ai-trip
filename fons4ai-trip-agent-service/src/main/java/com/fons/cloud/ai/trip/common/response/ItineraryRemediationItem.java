package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItineraryRemediationActionType;

import java.util.List;

/**
 * 根据审核问题生成的整改动作；instruction 仅供解释，不作为代码分支条件。
 *
 * @author hongqy
 */
public record ItineraryRemediationItem(String remediationId,
                                       int priority,
                                       List<String> issueIds,
                                       ItineraryRemediationActionType actionType,
                                       List<String> affectedProposalIds,
                                       List<String> candidateIds,
                                       String instruction) {

    public ItineraryRemediationItem {
        issueIds = issueIds == null ? List.of() : List.copyOf(issueIds);
        affectedProposalIds = affectedProposalIds == null ? List.of() : List.copyOf(affectedProposalIds);
        candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
    }
}
