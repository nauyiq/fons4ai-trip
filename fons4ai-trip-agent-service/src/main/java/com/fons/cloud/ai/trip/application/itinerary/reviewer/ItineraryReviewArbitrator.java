package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import cn.hutool.core.util.IdUtil;
import com.fons.cloud.ai.trip.common.constants.ItineraryRemediationActionType;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.dto.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.dto.ItineraryReviewContext;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReview;
import com.fons.cloud.ai.trip.common.response.ItineraryProposalReview;
import com.fons.cloud.ai.trip.common.response.ItineraryRemediationItem;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewIssue;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
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

    private final Set<ItineraryReviewDimension> requiredDimensions;
    private final ItineraryRemediationPlanner remediationPlanner = new ItineraryRemediationPlanner();

    public ItineraryReviewArbitrator(Set<ItineraryReviewDimension> requiredDimensions) {
        if (requiredDimensions == null || requiredDimensions.isEmpty()) {
            throw SystemIntervalException.of("审核规则集必需维度不能为空");
        }
        for (ItineraryReviewDimension dimension : requiredDimensions) {
            if (dimension == null) {
                throw SystemIntervalException.of("审核规则集必需维度不能包含null");
            }
        }
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
        // 1. 归并各维度结论，并按问题标识去重
        if (context == null) {
            throw SystemIntervalException.of("审核上下文不能为空");
        }
        List<ItineraryDimensionReviewResult> normalizedResults = dimensionResults == null
                ? List.of() : dimensionResults.stream().filter(Objects::nonNull).toList();
        List<ItineraryDimensionReview> dimensionReviews = normalizedResults.stream()
                .map(ItineraryDimensionReviewResult::dimensionReview)
                .filter(Objects::nonNull)
                .toList();
        List<ItineraryReviewIssue> issues = deduplicateIssues(normalizedResults);
        // 2. 判断必需维度是否完成，并计算每个代表方案的审核结论
        ItineraryReviewExecutionStatus executionStatus = executionStatusOf(dimensionReviews);
        List<ItineraryProposalReview> proposalReviews = proposalReviewsOf(planningResult, issues, executionStatus);
        // 3. 从可推荐方案中选出最合适的方案，确定整次审核的业务结论
        String recommendedProposalId = recommendedProposalId(planningResult, proposalReviews);
        ItineraryReviewVerdict verdict = overallVerdict(
                dimensionReviews, proposalReviews, issues, recommendedProposalId, executionStatus);
        // 4. 按问题生成整改项，再根据审核状态与整改项确定下一步动作
        List<ItineraryRemediationItem> remediationItems = remediationPlanner.plan(planningResult, issues);
        ItineraryReviewNextAction nextAction = nextActionOf(
                executionStatus, dimensionReviews, proposalReviews,
                recommendedProposalId, issues, remediationItems);

        // 5. 汇总覆盖范围与审核结果，生成可保存和展示的结构化报告
        String summary = summaryOf(executionStatus, verdict, recommendedProposalId, proposalReviews, issues);
        if (!context.coverageGaps().isEmpty()) {
            summary += "；部分审核信息未覆盖，详见维度摘要";
        }
        return ItineraryReviewResult.builder()
                .reviewId("review_" + IdUtil.fastSimpleUUID())
                .planId(planningResult == null ? null : planningResult.getPlanId())
                .reviewedAt(context.reviewedAt())
                .executionStatus(executionStatus)
                .verdict(verdict)
                .recommendedProposalId(recommendedProposalId)
                .summary(summary)
                .proposalReviews(proposalReviews)
                .dimensionReviews(dimensionReviews)
                .issues(issues)
                .remediationItems(remediationItems)
                .nextAction(nextAction)
                .build();
    }

    private List<ItineraryReviewIssue> deduplicateIssues(List<ItineraryDimensionReviewResult> dimensionResults) {
        Map<String, ItineraryReviewIssue> issues = new LinkedHashMap<>();
        for (ItineraryDimensionReviewResult result : dimensionResults) {
            for (ItineraryReviewIssue issue : result.issues()) {
                if (issue != null && StringUtils.isNotBlank(issue.issueId())) {
                    ItineraryReviewIssue existing = issues.putIfAbsent(issue.issueId(), issue);
                    if (existing != null && !existing.equals(issue)) {
                        throw SystemIntervalException.of("审核问题标识冲突：" + issue.issueId());
                    }
                }
            }
        }
        return List.copyOf(issues.values());
    }

    private ItineraryReviewExecutionStatus executionStatusOf(List<ItineraryDimensionReview> dimensionReviews) {
        if (dimensionReviews.isEmpty()) {
            return ItineraryReviewExecutionStatus.FAILED;
        }
        List<ItineraryDimensionReview> requiredReviews = dimensionReviews.stream()
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
                .map(ItineraryDimensionReview::dimension)
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

    private List<ItineraryProposalReview> proposalReviewsOf(ItineraryPlanningResult planningResult,
                                                   List<ItineraryReviewIssue> issues,
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

        List<ItineraryProposalReview> proposalReviews = new ArrayList<>();
        for (String proposalId : proposalIds) {
            List<ItineraryReviewIssue> proposalIssues = issues.stream()
                    .filter(issue -> affectsProposal(issue, proposalId))
                    .toList();
            ItineraryReviewVerdict verdict = ItineraryReviewSupport.verdictOf(proposalIssues);
            boolean eligible = executionStatus == ItineraryReviewExecutionStatus.COMPLETE
                    && verdict != ItineraryReviewVerdict.BLOCKED;
            List<String> issueIds = proposalIssues.stream().map(ItineraryReviewIssue::issueId).toList();
            proposalReviews.add(new ItineraryProposalReview(proposalId, verdict, eligible, issueIds));
        }
        return List.copyOf(proposalReviews);
    }

    private boolean affectsProposal(ItineraryReviewIssue issue, String proposalId) {
        return issue.affectedProposalIds().isEmpty() || issue.affectedProposalIds().contains(proposalId);
    }

    private String recommendedProposalId(ItineraryPlanningResult planningResult,
                                         List<ItineraryProposalReview> proposalReviews) {
        if (planningResult == null || planningResult.getProposals() == null) {
            return null;
        }
        Map<String, Proposal> proposals = new LinkedHashMap<>();
        for (Proposal proposal : planningResult.getProposals()) {
            if (proposal != null && StringUtils.isNotBlank(proposal.proposalId())) {
                proposals.putIfAbsent(proposal.proposalId(), proposal);
            }
        }
        ItineraryProposalReview best = null;
        for (ItineraryProposalReview candidate : proposalReviews) {
            if (!candidate.eligibleForRecommendation()) {
                continue;
            }
            if (best == null || compareRecommendation(candidate, best, proposals) < 0) {
                best = candidate;
            }
        }
        return best == null ? null : best.proposalId();
    }

    private int compareRecommendation(ItineraryProposalReview left,
                                      ItineraryProposalReview right,
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

    private ItineraryReviewVerdict overallVerdict(List<ItineraryDimensionReview> dimensionReviews,
                                                   List<ItineraryProposalReview> proposalReviews,
                                                   List<ItineraryReviewIssue> issues,
                                                   String recommendedProposalId,
                                                   ItineraryReviewExecutionStatus executionStatus) {
        if (executionStatus == ItineraryReviewExecutionStatus.COMPLETE) {
            if (recommendedProposalId != null) {
                return proposalReviews.stream()
                        .filter(review -> recommendedProposalId.equals(review.proposalId()))
                        .map(ItineraryProposalReview::verdict)
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

    private ItineraryReviewNextAction nextActionOf(ItineraryReviewExecutionStatus executionStatus,
                                                    List<ItineraryDimensionReview> dimensionReviews,
                                                    List<ItineraryProposalReview> proposalReviews,
                                                    String recommendedProposalId,
                                                    List<ItineraryReviewIssue> issues,
                                                    List<ItineraryRemediationItem> remediationItems) {
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
        if (issues.stream().anyMatch(ItineraryReviewIssue::repairableByReplanning)) {
            return ItineraryReviewNextAction.REPLAN;
        }
        return ItineraryReviewNextAction.STOP_NO_FEASIBLE_PROPOSAL;
    }

    private ItineraryReviewNextAction incompleteReviewAction(List<ItineraryRemediationItem> remediationItems) {
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

    private boolean hasAction(List<ItineraryRemediationItem> remediationItems,
                              ItineraryRemediationActionType actionType) {
        return remediationItems.stream().anyMatch(item -> item.actionType() == actionType);
    }

    private String summaryOf(ItineraryReviewExecutionStatus executionStatus,
                             ItineraryReviewVerdict verdict,
                             String recommendedProposalId,
                             List<ItineraryProposalReview> proposalReviews,
                             List<ItineraryReviewIssue> issues) {
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
        return "审核完成，推荐方案" + recommendedProposalId + "通过当前审核";
    }
}
