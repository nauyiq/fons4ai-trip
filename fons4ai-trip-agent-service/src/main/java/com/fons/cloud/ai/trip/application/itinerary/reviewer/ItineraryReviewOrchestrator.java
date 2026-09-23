package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.DimensionReview;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Objects;

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
     * 创建当前第一阶段的客观审核编排器。
     */
    public ItineraryReviewOrchestrator() {
        this(ItineraryReviewRuleSet.objectiveV1());
    }

    /**
     * 使用明确规则集创建编排器，便于后续增加主观评估适配器且不丢失完整性约束。
     *
     * @param ruleSet 审核规则集
     */
    public ItineraryReviewOrchestrator(ItineraryReviewRuleSet ruleSet) {
        this.ruleSet = Objects.requireNonNull(ruleSet, "审核规则集不能为空");
        this.arbitrator = new ItineraryReviewArbitrator(
                ruleSet.version(), ruleSet.requiredDimensions());
    }

    /**
     * 执行当前规则集中的全部审核维度，并形成统一审核结果。
     * 该便捷入口仅适用于未关联差旅单的独立规划；关联差旅单时应使用带审核上下文的重载方法。
     *
     * @param planningResult 已保存的不可变规划结果
     * @return 完整审核结果
     */
    public ItineraryReviewResult review(ItineraryPlanningResult planningResult) {
        return review(planningResult, ItineraryReviewContext.now(null));
    }

    /**
     * 使用应用层准备的可信审核上下文执行审核。
     */
    public ItineraryReviewResult review(ItineraryPlanningResult planningResult,
                                        ItineraryReviewContext context) {
        Objects.requireNonNull(context, "审核上下文不能为空");
        if (planningResult == null || StringUtils.isBlank(planningResult.getPlanId())) {
            throw new IllegalArgumentException("被审核的规划结果及planId不能为空");
        }
        List<ItineraryDimensionReviewResult> dimensionResults = ruleSet.reviewers().stream()
                .map(reviewer -> safeReview(reviewer, planningResult, context))
                .toList();
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
        DimensionReview dimensionReview = new DimensionReview(reviewer.dimension(),
                ItineraryReviewDimensionStatus.FAILED, reviewer.reviewerType(),
                reviewer.reviewerVersion(), null, List.of(), summary);
        return new ItineraryDimensionReviewResult(dimensionReview, List.of());
    }

}
