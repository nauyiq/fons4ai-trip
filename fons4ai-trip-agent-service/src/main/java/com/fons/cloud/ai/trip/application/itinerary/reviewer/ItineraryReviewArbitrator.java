package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import cn.hutool.core.util.IdUtil;
import com.fons.cloud.ai.trip.common.constants.ItineraryRemediationActionType;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewIssueCode;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.DimensionReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ProposalReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.RemediationItem;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewEvidence;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewIssue;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 行程审核结果仲裁器。
 * 将各维度审核结果归并为方案级结论、最终推荐、整改项和下一步动作。
 * 仲裁完全基于结构化字段，不解析审核器生成的自然语言内容。
 *
 * @author hongqy
 */
public final class ItineraryReviewArbitrator {

    private final String ruleSetVersion;
    private final Set<ItineraryReviewDimension> requiredDimensions;

    public ItineraryReviewArbitrator(
            String ruleSetVersion,
            Set<ItineraryReviewDimension> requiredDimensions) {
        if (StringUtils.isBlank(ruleSetVersion)) {
            throw new IllegalArgumentException("审核规则集版本不能为空");
        }
        if (requiredDimensions == null || requiredDimensions.isEmpty()) {
            throw new IllegalArgumentException("审核规则集必需维度不能为空");
        }
        this.ruleSetVersion = ruleSetVersion.trim();
        this.requiredDimensions = Set.copyOf(requiredDimensions);
    }

    /**
     * 汇总当前规划的全部维度审核结果。
     * 推荐时优先选择审核通过方案，再按照规划综合分选择同结论中的最佳方案。
     *
     * @param planningResult 被审核的规划结果
     * @param dimensionResults 各维度结构化审核结果
     * @return 完整审核结果
     */
    public ItineraryReviewResult arbitrate(ItineraryPlanningResult planningResult,
                                           List<ItineraryDimensionReviewResult> dimensionResults,
                                           ItineraryReviewContext context) {
        Objects.requireNonNull(context, "审核上下文不能为空");
        List<ItineraryDimensionReviewResult> normalizedResults = dimensionResults == null
                ? List.of() : dimensionResults.stream().filter(Objects::nonNull).toList();
        List<DimensionReview> dimensionReviews = normalizedResults.stream()
                .map(ItineraryDimensionReviewResult::dimensionReview)
                .filter(Objects::nonNull)
                .toList();
        List<ReviewIssue> issues = deduplicateIssues(normalizedResults);
        ItineraryReviewExecutionStatus executionStatus = executionStatusOf(dimensionReviews);
        List<ProposalReview> proposalReviews = proposalReviewsOf(planningResult, issues, executionStatus);
        String recommendedProposalId = recommendedProposalId(planningResult, proposalReviews);
        ItineraryReviewVerdict verdict = overallVerdict(
                dimensionReviews, proposalReviews, issues, recommendedProposalId, executionStatus);
        List<RemediationItem> remediationItems = remediationItemsOf(planningResult, issues);
        ItineraryReviewNextAction nextAction = nextActionOf(
                executionStatus, dimensionReviews, proposalReviews,
                recommendedProposalId, issues, remediationItems);

        return ItineraryReviewResult.builder()
                .reviewId("review_" + IdUtil.fastSimpleUUID())
                .planId(planningResult == null ? null : planningResult.getPlanId())
                .ruleSetVersion(ruleSetVersion)
                .reviewedAt(context.reviewedAt())
                .executionStatus(executionStatus)
                .verdict(verdict)
                .recommendedProposalId(recommendedProposalId)
                .summary(summaryOf(executionStatus, verdict, recommendedProposalId, proposalReviews, issues))
                .proposalReviews(proposalReviews)
                .dimensionReviews(dimensionReviews)
                .issues(issues)
                .remediationItems(remediationItems)
                .nextAction(nextAction)
                .build();
    }

    private List<ReviewIssue> deduplicateIssues(List<ItineraryDimensionReviewResult> dimensionResults) {
        Map<String, ReviewIssue> issues = new LinkedHashMap<>();
        for (ItineraryDimensionReviewResult result : dimensionResults) {
            for (ReviewIssue issue : result.issues()) {
                if (issue != null && StringUtils.isNotBlank(issue.issueId())) {
                    ReviewIssue existing = issues.putIfAbsent(issue.issueId(), issue);
                    if (existing != null && !existing.equals(issue)) {
                        throw new IllegalStateException("审核问题标识冲突：" + issue.issueId());
                    }
                }
            }
        }
        return List.copyOf(issues.values());
    }

    private ItineraryReviewExecutionStatus executionStatusOf(List<DimensionReview> dimensionReviews) {
        if (dimensionReviews.isEmpty()) {
            return ItineraryReviewExecutionStatus.FAILED;
        }
        List<DimensionReview> requiredReviews = dimensionReviews.stream()
                .filter(review -> requiredDimensions.contains(review.dimension()))
                .toList();
        long evaluatedCount = requiredReviews.stream()
                .filter(review -> review.status() == ItineraryReviewDimensionStatus.COMPLETE
                        || review.status() == ItineraryReviewDimensionStatus.PARTIAL
                        || review.status() == ItineraryReviewDimensionStatus.NOT_APPLICABLE)
                .count();
        if (evaluatedCount == 0) {
            return ItineraryReviewExecutionStatus.FAILED;
        }
        long distinctDimensionCount = requiredReviews.stream()
                .map(DimensionReview::dimension)
                .distinct()
                .count();
        boolean incomplete = distinctDimensionCount != requiredDimensions.size()
                || requiredReviews.size() != requiredDimensions.size()
                || requiredReviews.stream().anyMatch(review ->
                review.status() == null
                        || review.status() == ItineraryReviewDimensionStatus.PARTIAL
                        || review.status() == ItineraryReviewDimensionStatus.NOT_EVALUATED
                        || review.status() == ItineraryReviewDimensionStatus.FAILED);
        return incomplete ? ItineraryReviewExecutionStatus.PARTIAL : ItineraryReviewExecutionStatus.COMPLETE;
    }

    private List<ProposalReview> proposalReviewsOf(ItineraryPlanningResult planningResult,
                                                   List<ReviewIssue> issues,
                                                   ItineraryReviewExecutionStatus executionStatus) {
        if (planningResult == null || planningResult.getProposals() == null) {
            return List.of();
        }
        Set<String> proposalIds = new LinkedHashSet<>();
        for (Proposal proposal : planningResult.getProposals()) {
            if (proposal != null && StringUtils.isNotBlank(proposal.proposalId())) {
                proposalIds.add(proposal.proposalId());
            }
        }

        List<ProposalReview> proposalReviews = new ArrayList<>();
        for (String proposalId : proposalIds) {
            List<ReviewIssue> proposalIssues = issues.stream()
                    .filter(issue -> affectsProposal(issue, proposalId))
                    .toList();
            ItineraryReviewVerdict verdict = ItineraryReviewSupport.verdictOf(proposalIssues);
            boolean eligible = executionStatus == ItineraryReviewExecutionStatus.COMPLETE
                    && verdict != ItineraryReviewVerdict.BLOCKED;
            List<String> issueIds = proposalIssues.stream().map(ReviewIssue::issueId).toList();
            proposalReviews.add(new ProposalReview(proposalId, verdict, eligible, issueIds));
        }
        return List.copyOf(proposalReviews);
    }

    private boolean affectsProposal(ReviewIssue issue, String proposalId) {
        return issue.affectedProposalIds().isEmpty() || issue.affectedProposalIds().contains(proposalId);
    }

    private String recommendedProposalId(ItineraryPlanningResult planningResult,
                                         List<ProposalReview> proposalReviews) {
        if (planningResult == null || planningResult.getProposals() == null) {
            return null;
        }
        Map<String, Proposal> proposals = new LinkedHashMap<>();
        for (Proposal proposal : planningResult.getProposals()) {
            if (proposal != null && StringUtils.isNotBlank(proposal.proposalId())) {
                proposals.putIfAbsent(proposal.proposalId(), proposal);
            }
        }
        ProposalReview best = null;
        for (ProposalReview candidate : proposalReviews) {
            if (!candidate.eligibleForRecommendation()) {
                continue;
            }
            if (best == null || compareRecommendation(candidate, best, proposals) < 0) {
                best = candidate;
            }
        }
        return best == null ? null : best.proposalId();
    }

    private int compareRecommendation(ProposalReview left,
                                      ProposalReview right,
                                      Map<String, Proposal> proposals) {
        int verdictComparison = Integer.compare(verdictRank(left.verdict()), verdictRank(right.verdict()));
        if (verdictComparison != 0) {
            return verdictComparison;
        }
        Proposal leftProposal = proposals.get(left.proposalId());
        Proposal rightProposal = proposals.get(right.proposalId());
        return rightProposal.scores().overall().compareTo(leftProposal.scores().overall());
    }

    private int verdictRank(ItineraryReviewVerdict verdict) {
        return switch (verdict) {
            case PASS -> 0;
            case WARNING -> 1;
            case BLOCKED -> 2;
        };
    }

    private ItineraryReviewVerdict overallVerdict(List<DimensionReview> dimensionReviews,
                                                   List<ProposalReview> proposalReviews,
                                                   List<ReviewIssue> issues,
                                                   String recommendedProposalId,
                                                   ItineraryReviewExecutionStatus executionStatus) {
        if (executionStatus == ItineraryReviewExecutionStatus.COMPLETE) {
            if (recommendedProposalId != null) {
                return proposalReviews.stream()
                        .filter(review -> recommendedProposalId.equals(review.proposalId()))
                        .map(ProposalReview::verdict)
                        .findFirst()
                        .orElse(ItineraryReviewVerdict.PASS);
            }
            if (!proposalReviews.isEmpty()) {
                return ItineraryReviewVerdict.BLOCKED;
            }
        }
        if (!issues.isEmpty()) {
            return ItineraryReviewSupport.verdictOf(issues);
        }
        if (dimensionReviews.stream().anyMatch(
                review -> review.verdict() == ItineraryReviewVerdict.BLOCKED)) {
            return ItineraryReviewVerdict.BLOCKED;
        }
        if (dimensionReviews.stream().anyMatch(
                review -> review.verdict() == ItineraryReviewVerdict.WARNING)) {
            return ItineraryReviewVerdict.WARNING;
        }
        if (dimensionReviews.stream().anyMatch(
                review -> review.verdict() == ItineraryReviewVerdict.PASS)) {
            return ItineraryReviewVerdict.PASS;
        }
        return null;
    }

    private List<RemediationItem> remediationItemsOf(ItineraryPlanningResult planningResult,
                                                      List<ReviewIssue> issues) {
        List<ReviewIssue> actionableIssues = issues.stream()
                .filter(issue -> issue.severity() != ItineraryReviewSeverity.ADVISORY)
                .sorted(Comparator.comparingInt(this::priorityOf))
                .toList();
        List<RemediationItem> items = new ArrayList<>();
        for (int index = 0; index < actionableIssues.size(); index++) {
            ReviewIssue issue = actionableIssues.get(index);
            ItineraryRemediationActionType actionType = actionTypeOf(issue);
            items.add(new RemediationItem("remediation_" + (index + 1), index + 1,
                    List.of(issue.issueId()), actionType, issue.affectedProposalIds(),
                    candidateIdsOf(planningResult, issue, actionType), issue.message()));
        }
        return List.copyOf(items);
    }

    private int priorityOf(ReviewIssue issue) {
        return switch (issue.severity()) {
            case BLOCKING -> 1;
            case WARNING -> 2;
            case ADVISORY -> 3;
        };
    }

    private ItineraryRemediationActionType actionTypeOf(ReviewIssue issue) {
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
            case PROPOSAL_COMPONENT_MISSING -> componentMissingAction(issue);
        };
    }

    private ItineraryRemediationActionType policyEvidenceAction(ReviewIssue issue) {
        String field = evidenceField(issue);
        if (field.contains("policy")) {
            return ItineraryRemediationActionType.LOAD_TRAVEL_POLICY;
        }
        return componentAction(field);
    }

    private ItineraryRemediationActionType componentMissingAction(ReviewIssue issue) {
        return componentAction(evidenceField(issue));
    }

    private String evidenceField(ReviewIssue issue) {
        return issue.evidence().stream()
                .map(ReviewEvidence::field)
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
                                        ReviewIssue issue,
                                        ItineraryRemediationActionType actionType) {
        if (actionType != ItineraryRemediationActionType.RESEARCH_HOTEL
                && actionType != ItineraryRemediationActionType.RESEARCH_TRANSPORT
                && actionType != ItineraryRemediationActionType.EXCLUDE_CANDIDATE) {
            return List.of();
        }
        String planId = planningResult == null ? null : planningResult.getPlanId();
        return issue.evidence().stream()
                .map(ReviewEvidence::referenceId)
                .filter(StringUtils::isNotBlank)
                .filter(referenceId -> !referenceId.equals(planId))
                .filter(referenceId -> !issue.affectedProposalIds().contains(referenceId))
                .distinct()
                .toList();
    }

    private ItineraryReviewNextAction nextActionOf(ItineraryReviewExecutionStatus executionStatus,
                                                    List<DimensionReview> dimensionReviews,
                                                    List<ProposalReview> proposalReviews,
                                                    String recommendedProposalId,
                                                    List<ReviewIssue> issues,
                                                    List<RemediationItem> remediationItems) {
        if (executionStatus == ItineraryReviewExecutionStatus.FAILED
                || dimensionReviews.stream().anyMatch(review ->
                review.status() == ItineraryReviewDimensionStatus.FAILED)) {
            return ItineraryReviewNextAction.RETRY_REVIEW;
        }
        if (executionStatus == ItineraryReviewExecutionStatus.PARTIAL) {
            return incompleteReviewAction(remediationItems);
        }
        if (recommendedProposalId != null) {
            return ItineraryReviewNextAction.PROCEED;
        }
        if (proposalReviews.isEmpty()) {
            return ItineraryReviewNextAction.STOP_NO_FEASIBLE_PROPOSAL;
        }
        if (remediationItems.stream().anyMatch(item ->
                item.actionType() == ItineraryRemediationActionType.REQUEST_USER_INPUT)) {
            return ItineraryReviewNextAction.REQUEST_USER_INPUT;
        }
        if (issues.stream().anyMatch(ReviewIssue::repairableByReplanning)) {
            return ItineraryReviewNextAction.REPLAN;
        }
        return ItineraryReviewNextAction.STOP_NO_FEASIBLE_PROPOSAL;
    }

    private ItineraryReviewNextAction incompleteReviewAction(List<RemediationItem> remediationItems) {
        if (hasAction(remediationItems, ItineraryRemediationActionType.REQUEST_USER_INPUT)) {
            return ItineraryReviewNextAction.REQUEST_USER_INPUT;
        }
        if (hasAction(remediationItems, ItineraryRemediationActionType.LOAD_TRAVEL_POLICY)
                || hasAction(remediationItems, ItineraryRemediationActionType.RESEARCH_TRANSPORT)
                || hasAction(remediationItems, ItineraryRemediationActionType.RESEARCH_HOTEL)
                || hasAction(remediationItems, ItineraryRemediationActionType.REPLAN)
                || hasAction(remediationItems, ItineraryRemediationActionType.EXCLUDE_CANDIDATE)) {
            return ItineraryReviewNextAction.REPLAN;
        }
        return ItineraryReviewNextAction.RETRY_REVIEW;
    }

    private boolean hasAction(List<RemediationItem> remediationItems,
                              ItineraryRemediationActionType actionType) {
        return remediationItems.stream().anyMatch(item -> item.actionType() == actionType);
    }

    private String summaryOf(ItineraryReviewExecutionStatus executionStatus,
                             ItineraryReviewVerdict verdict,
                             String recommendedProposalId,
                             List<ProposalReview> proposalReviews,
                             List<ReviewIssue> issues) {
        if (executionStatus == ItineraryReviewExecutionStatus.FAILED) {
            return "审核未能形成有效结果，请检查规划数据及审核器执行状态";
        }
        if (executionStatus == ItineraryReviewExecutionStatus.PARTIAL) {
            return "部分审核维度未完成，当前已识别" + issues.size() + "个问题，暂不推荐具体方案";
        }
        if (recommendedProposalId == null) {
            return "审核完成，" + proposalReviews.size() + "个代表方案均存在阻断问题";
        }
        long warningCount = issues.stream()
                .filter(issue -> affectsProposal(issue, recommendedProposalId))
                .filter(issue -> issue.severity() == ItineraryReviewSeverity.WARNING)
                .count();
        if (verdict == ItineraryReviewVerdict.WARNING) {
            return "审核完成，推荐方案" + recommendedProposalId + "存在" + warningCount + "个待关注事项";
        }
        return "审核完成，推荐方案" + recommendedProposalId + "通过当前客观规则审核";
    }
}
