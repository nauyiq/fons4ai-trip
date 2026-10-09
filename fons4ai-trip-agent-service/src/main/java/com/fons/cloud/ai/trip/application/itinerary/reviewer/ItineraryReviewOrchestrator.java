package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.dto.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.dto.ItineraryReviewContext;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReview;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 行程规划审核编排器。
 * 按固定顺序执行各维度审核器，并将维度输出交给 Java 仲裁器生成完整审核结果。
 * 审核器之间没有共享可变状态，单个维度执行失败不会阻止其他维度给出结论。
 *
 * @author hongqy
 */
@Slf4j
public final class ItineraryReviewOrchestrator {

    private final ItineraryReviewRuleSet ruleSet;
    private final ItineraryReviewArbitrator arbitrator;

    /**
     * 使用明确规则集创建编排器，便于验证必需维度与审核器一致。
     *
     * @param ruleSet 审核规则集
     */
    public ItineraryReviewOrchestrator(ItineraryReviewRuleSet ruleSet) {
        if (ruleSet == null) {
            throw SystemIntervalException.of("审核规则集不能为空");
        }
        this.ruleSet = ruleSet;
        this.arbitrator = new ItineraryReviewArbitrator(ruleSet.requiredDimensions());
    }

    /**
     * 使用应用层准备的可信审核上下文执行审核。
     */
    public ItineraryReviewResult review(ItineraryPlanningResult planningResult,
                                        ItineraryReviewContext context) {
        // 1. 确认规划标识与本轮可信审核上下文均可用
        if (context == null) {
            throw SystemIntervalException.of("审核上下文不能为空");
        }
        if (planningResult == null || StringUtils.isBlank(planningResult.getPlanId())) {
            throw SystemIntervalException.of("被审核的规划结果及planId不能为空");
        }
        // 2. 按规则集逐一执行维度审核，将单个审核器失败记录为该维度失败
        List<ItineraryDimensionReviewResult> dimensionResults = ruleSet.reviewers().stream()
                .map(reviewer -> safeReview(reviewer, planningResult, context))
                .toList();
        // 3. 汇总维度结果，计算方案结论、推荐及下一步动作
        return arbitrator.arbitrate(planningResult, dimensionResults, context);
    }

    private ItineraryDimensionReviewResult safeReview(ItineraryDimensionReviewer reviewer,
                                                       ItineraryPlanningResult planningResult,
                                                       ItineraryReviewContext context) {
        try {
            ItineraryDimensionReviewResult result = reviewer.review(planningResult, context);
            if (result == null || result.dimensionReview() == null
                    || result.dimensionReview().dimension() != reviewer.dimension()) {
                log.error("Itinerary review result does not match registered dimension: reviewer={}, dimension={}",
                        reviewer.getClass().getName(), reviewer.dimension());
                return failedResult(reviewer, "审核器未返回与注册维度一致的结果");
            }
            return result;
        } catch (RuntimeException e) {
            log.error("Itinerary reviewer execution failed: reviewer={}, dimension={}",
                    reviewer.getClass().getName(), reviewer.dimension(), e);
            return failedResult(reviewer, "审核器执行异常，未形成该维度结论");
        }
    }

    private ItineraryDimensionReviewResult failedResult(ItineraryDimensionReviewer reviewer, String summary) {
        ItineraryDimensionReview dimensionReview = new ItineraryDimensionReview(reviewer.dimension(),
                ItineraryReviewDimensionStatus.FAILED, reviewer.reviewerType(),
                reviewer.reviewerVersion(), null, List.of(), summary);
        return new ItineraryDimensionReviewResult(dimensionReview, List.of());
    }

    /**
     * 一套可执行的行程审核规则集，明确必需维度及对应审核器。
     *
     * @param requiredDimensions 声明完成审核所必须执行的维度
     * @param reviewers 当前规则集的审核器
     * @author hongqy
     */
    public record ItineraryReviewRuleSet(Set<ItineraryReviewDimension> requiredDimensions,
                                         List<ItineraryDimensionReviewer> reviewers) {

        public ItineraryReviewRuleSet {
            if (requiredDimensions == null || requiredDimensions.isEmpty()) {
                throw SystemIntervalException.of("审核规则集必需维度不能为空");
            }
            if (reviewers == null || reviewers.isEmpty()) {
                throw SystemIntervalException.of("审核规则集审核器不能为空");
            }
            for (ItineraryReviewDimension dimension : requiredDimensions) {
                if (dimension == null) {
                    throw SystemIntervalException.of("审核规则集必需维度不能包含null");
                }
            }
            validateReviewers(requiredDimensions, reviewers);
            requiredDimensions = Set.copyOf(requiredDimensions);
            reviewers = List.copyOf(reviewers);
        }

        private static void validateReviewers(Set<ItineraryReviewDimension> requiredDimensions,
                                              List<ItineraryDimensionReviewer> reviewers) {
            EnumSet<ItineraryReviewDimension> registeredDimensions =
                    EnumSet.noneOf(ItineraryReviewDimension.class);
            for (ItineraryDimensionReviewer reviewer : reviewers) {
                if (reviewer == null) {
                    throw SystemIntervalException.of("行程审核器不能包含null");
                }
                ItineraryReviewDimension dimension = reviewer.dimension();
                if (dimension == null) {
                    throw SystemIntervalException.of("行程审核维度不能为空");
                }
                if (!registeredDimensions.add(dimension)) {
                    throw SystemIntervalException.of("行程审核维度重复注册：" + dimension);
                }
            }
            if (!registeredDimensions.equals(requiredDimensions)) {
                EnumSet<ItineraryReviewDimension> missingDimensions = EnumSet.copyOf(requiredDimensions);
                missingDimensions.removeAll(registeredDimensions);
                EnumSet<ItineraryReviewDimension> unexpectedDimensions = EnumSet.copyOf(registeredDimensions);
                unexpectedDimensions.removeAll(requiredDimensions);
                throw SystemIntervalException.of("审核规则集维度与审核器不一致：missing="
                        + missingDimensions + "，unexpected=" + unexpectedDimensions);
            }
        }
    }


}
