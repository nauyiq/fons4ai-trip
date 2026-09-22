package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import org.apache.commons.lang3.StringUtils;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 一套可执行的行程审核规则集，明确版本、必需维度及对应审核器。
 *
 * @param version 规则集稳定版本
 * @param requiredDimensions 声明完成审核所必须执行的维度
 * @param reviewers 当前规则集的审核器
 * @author hongqy
 */
public record ItineraryReviewRuleSet(String version,
                                     Set<ItineraryReviewDimension> requiredDimensions,
                                     List<ItineraryDimensionReviewer> reviewers) {

    public ItineraryReviewRuleSet {
        if (StringUtils.isBlank(version)) {
            throw new IllegalArgumentException("审核规则集版本不能为空");
        }
        if (requiredDimensions == null || requiredDimensions.isEmpty()) {
            throw new IllegalArgumentException("审核规则集必需维度不能为空");
        }
        if (reviewers == null || reviewers.isEmpty()) {
            throw new IllegalArgumentException("审核规则集审核器不能为空");
        }
        version = version.trim();
        requiredDimensions = Set.copyOf(requiredDimensions);
        reviewers = List.copyOf(reviewers);
        validateReviewers(requiredDimensions, reviewers);
    }

    /**
     * 当前第一阶段客观审核规则集。
     */
    public static ItineraryReviewRuleSet objectiveV1() {
        Set<ItineraryReviewDimension> requiredDimensions = EnumSet.of(
                ItineraryReviewDimension.TRAVEL_ORDER_CONSISTENCY,
                ItineraryReviewDimension.POLICY_COMPLIANCE,
                ItineraryReviewDimension.EXECUTION_FEASIBILITY);
        List<ItineraryDimensionReviewer> reviewers = List.of(
                new TravelOrderConsistencyReviewer(),
                new TravelPolicyComplianceReviewer(),
                new ItineraryExecutionFeasibilityReviewer());
        return new ItineraryReviewRuleSet("trip-objective-review-v1", requiredDimensions, reviewers);
    }

    private static void validateReviewers(Set<ItineraryReviewDimension> requiredDimensions,
                                          List<ItineraryDimensionReviewer> reviewers) {
        EnumSet<ItineraryReviewDimension> registeredDimensions =
                EnumSet.noneOf(ItineraryReviewDimension.class);
        for (ItineraryDimensionReviewer reviewer : reviewers) {
            Objects.requireNonNull(reviewer, "行程审核器不能包含null");
            ItineraryReviewDimension dimension = Objects.requireNonNull(
                    reviewer.dimension(), "行程审核维度不能为空");
            if (!registeredDimensions.add(dimension)) {
                throw new IllegalArgumentException("行程审核维度重复注册：" + dimension);
            }
        }
        if (!registeredDimensions.equals(requiredDimensions)) {
            EnumSet<ItineraryReviewDimension> missingDimensions = EnumSet.copyOf(requiredDimensions);
            missingDimensions.removeAll(registeredDimensions);
            EnumSet<ItineraryReviewDimension> unexpectedDimensions = EnumSet.copyOf(registeredDimensions);
            unexpectedDimensions.removeAll(requiredDimensions);
            throw new IllegalArgumentException("审核规则集维度与审核器不一致：missing="
                    + missingDimensions + "，unexpected=" + unexpectedDimensions);
        }
    }
}
