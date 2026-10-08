package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.common.base.exception.SystemIntervalException;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

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
