package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryRemediationActionType;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewIssueCode;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryRemediationItem;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewEvidence;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewIssue;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 将审核问题映射为按优先级排序的整改动作。
 *
 * @author hongqy
 */
final class ItineraryRemediationPlanner {

    List<ItineraryRemediationItem> plan(ItineraryPlanningResult planningResult,
                                       List<ItineraryReviewIssue> issues) {
        // 1. 排除仅供提示的问题，并按阻断、警告顺序排列其余问题
        List<ItineraryReviewIssue> actionableIssues = issues.stream()
                .filter(issue -> issue.severity() != ItineraryReviewSeverity.ADVISORY)
                .sorted(Comparator.comparingInt(this::priorityOf))
                .toList();
        // 2. 将每个问题映射为整改动作，附上可追溯的方案和候选标识
        List<ItineraryRemediationItem> items = new ArrayList<>();
        for (int index = 0; index < actionableIssues.size(); index++) {
            ItineraryReviewIssue issue = actionableIssues.get(index);
            ItineraryRemediationActionType actionType = actionTypeOf(issue);
            items.add(new ItineraryRemediationItem("remediation_" + (index + 1), index + 1,
                    List.of(issue.issueId()), actionType, issue.affectedProposalIds(),
                    candidateIdsOf(planningResult, issue, actionType), issue.message()));
        }
        return List.copyOf(items);
    }

    private int priorityOf(ItineraryReviewIssue issue) {
        return switch (issue.severity()) {
            case BLOCKING -> 1;
            case WARNING -> 2;
            case ADVISORY -> 3;
        };
    }

    private ItineraryRemediationActionType actionTypeOf(ItineraryReviewIssue issue) {
        ItineraryReviewIssueCode code = issue.code();
        return switch (code) {
            case TRAVEL_ORDER_ORIGIN_MISMATCH,
                 TRAVEL_ORDER_DESTINATION_MISMATCH,
                 TRAVEL_ORDER_DEPARTURE_DATE_MISMATCH,
                 TRAVEL_ORDER_RETURN_DATE_MISMATCH,
                 TRAVEL_ORDER_INACTIVE,
                 TRIP_DATE_RANGE_INVALID -> ItineraryRemediationActionType.REQUEST_USER_INPUT;
            case TRAVEL_ORDER_PENDING_APPROVAL -> ItineraryRemediationActionType.NO_ACTION;
            case APPROVAL_THRESHOLD_EXCEEDED,
                 ADVANCE_BOOKING_DAYS_INSUFFICIENT -> ItineraryRemediationActionType.REQUIRE_MANUAL_APPROVAL;
            case TRAVEL_POLICY_MISSING,
                 TRAVEL_POLICY_DESTINATION_MISMATCH -> ItineraryRemediationActionType.LOAD_TRAVEL_POLICY;
            case POLICY_EVIDENCE_MISSING -> policyEvidenceAction(issue);
            case HOTEL_RATE_LIMIT_EXCEEDED,
                 HOTEL_STAR_LIMIT_EXCEEDED,
                 HOTEL_CITY_MISMATCH,
                 HOTEL_STAY_MISMATCH -> ItineraryRemediationActionType.RESEARCH_HOTEL;
            case TRANSPORT_CABIN_LIMIT_EXCEEDED,
                 TRANSPORT_TYPE_INVALID,
                 TRANSPORT_ROUTE_MISMATCH,
                 TRANSPORT_DEPARTURE_DATE_MISMATCH,
                 TRANSPORT_TIME_INVALID,
                 TRANSPORT_DURATION_MISMATCH -> ItineraryRemediationActionType.RESEARCH_TRANSPORT;
            case PLANNING_CURRENCY_INVALID,
                 ROUND_TRIP_TIME_CONFLICT,
                 PROPOSAL_METRICS_MISMATCH,
                 PROPOSAL_SCORE_INVALID -> ItineraryRemediationActionType.REPLAN;
            case PROPOSAL_COMPONENT_MISSING -> componentAction(evidenceField(issue));
            case EXPERIENCE_OR_PREFERENCE_RISK, RESILIENCE_RISK -> ItineraryRemediationActionType.NO_ACTION;
        };
    }

    private ItineraryRemediationActionType policyEvidenceAction(ItineraryReviewIssue issue) {
        String field = evidenceField(issue);
        if (field.contains("policy")) {
            return ItineraryRemediationActionType.LOAD_TRAVEL_POLICY;
        }
        return componentAction(field);
    }

    private String evidenceField(ItineraryReviewIssue issue) {
        return issue.evidence().stream()
                .map(ItineraryReviewEvidence::field)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse("");
    }

    private ItineraryRemediationActionType componentAction(String field) {
        if (field.startsWith("hotel")) {
            return ItineraryRemediationActionType.RESEARCH_HOTEL;
        }
        if (field.startsWith("outbound") || field.startsWith("inbound")) {
            return ItineraryRemediationActionType.RESEARCH_TRANSPORT;
        }
        return ItineraryRemediationActionType.REPLAN;
    }

    private List<String> candidateIdsOf(ItineraryPlanningResult planningResult,
                                        ItineraryReviewIssue issue,
                                        ItineraryRemediationActionType actionType) {
        if (actionType != ItineraryRemediationActionType.RESEARCH_HOTEL
                && actionType != ItineraryRemediationActionType.RESEARCH_TRANSPORT
                && actionType != ItineraryRemediationActionType.EXCLUDE_CANDIDATE) {
            return List.of();
        }
        String planId = planningResult == null ? null : planningResult.getPlanId();
        return issue.evidence().stream()
                .map(ItineraryReviewEvidence::referenceId)
                .filter(StringUtils::isNotBlank)
                .filter(referenceId -> !referenceId.equals(planId))
                .filter(referenceId -> !issue.affectedProposalIds().contains(referenceId))
                .distinct()
                .toList();
    }
}
