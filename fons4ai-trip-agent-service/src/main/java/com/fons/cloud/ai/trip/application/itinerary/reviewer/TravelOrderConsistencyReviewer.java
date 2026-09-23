package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewEvidenceSource;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewIssueCode;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TravelOrderReference;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TripRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.DimensionReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewEvidence;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewIssue;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 差旅单一致性确定性审核器。
 * 比较规划请求与服务端验证后的差旅单快照，并检查差旅单是否仍处于可用于规划的状态。
 * 独立规划没有关联差旅单时，本维度返回 NOT_APPLICABLE。
 *
 * @author hongqy
 */
public final class TravelOrderConsistencyReviewer implements ItineraryDimensionReviewer {

    private static final String REVIEWER_VERSION = "travel-order-consistency-v1";

    @Override
    public ItineraryReviewDimension dimension() {
        return ItineraryReviewDimension.TRAVEL_ORDER_CONSISTENCY;
    }

    @Override
    public String reviewerVersion() {
        return REVIEWER_VERSION;
    }

    @Override
    public ItineraryDimensionReviewResult review(ItineraryPlanningResult planningResult,
                                                  ItineraryReviewContext context) {
        if (planningResult == null || planningResult.getUserRequest() == null) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划结果或行程请求缺失，无法执行差旅单一致性审核");
        }

        TravelOrderReference sourceOrder = planningResult.getSourceTravelOrder();
        if (sourceOrder == null) {
            return result(ItineraryReviewDimensionStatus.NOT_APPLICABLE, null, List.of(),
                    "本次为独立行程规划，未关联差旅单");
        }
        if (StringUtils.isBlank(sourceOrder.orderId())) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划关联的差旅单快照缺少差旅单号");
        }
        TravelOrderReference order = context.currentTravelOrder();
        if (order == null) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划关联了差旅单，但审核上下文未加载当前差旅单状态");
        }
        if (!StringUtils.equals(sourceOrder.orderId(), order.orderId())) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "审核上下文中的差旅单与规划关联差旅单不一致");
        }
        if (StringUtils.isAnyBlank(order.orderId(), order.origin(), order.destination())
                || order.status() == null || order.departureDate() == null || order.returnDate() == null) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "关联差旅单快照缺少单号、状态、城市或日期，无法完成一致性审核");
        }

        TripRequest request = planningResult.getUserRequest();
        if (StringUtils.isAnyBlank(request.origin(), request.destination())
                || request.departureDate() == null || request.returnDate() == null) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划行程请求缺少城市或日期，无法完成一致性审核");
        }
        List<String> proposalIds = proposalIds(planningResult);
        List<ReviewIssue> issues = new ArrayList<>();
        compareCity(issues, planningResult, context, order, proposalIds,
                "origin", request.origin(), order.origin(),
                ItineraryReviewIssueCode.TRAVEL_ORDER_ORIGIN_MISMATCH, "规划出发城市与差旅单不一致");
        compareCity(issues, planningResult, context, order, proposalIds,
                "destination", request.destination(),
                order.destination(), ItineraryReviewIssueCode.TRAVEL_ORDER_DESTINATION_MISMATCH,
                "规划目的城市与差旅单不一致");
        compareDate(issues, planningResult, context, order, proposalIds,
                "departureDate", request.departureDate(),
                order.departureDate(), ItineraryReviewIssueCode.TRAVEL_ORDER_DEPARTURE_DATE_MISMATCH,
                "规划去程日期与差旅单不一致");
        compareDate(issues, planningResult, context, order, proposalIds,
                "returnDate", request.returnDate(),
                order.returnDate(), ItineraryReviewIssueCode.TRAVEL_ORDER_RETURN_DATE_MISMATCH,
                "规划返程日期与差旅单不一致");
        reviewOrderStatus(issues, context, order, proposalIds);

        ItineraryReviewVerdict verdict = ItineraryReviewSupport.verdictOf(issues);
        String summary = issues.isEmpty()
                ? "规划城市、日期与关联差旅单一致，差旅单状态允许继续规划"
                : "差旅单一致性审核发现" + issues.size() + "个问题";
        return result(ItineraryReviewDimensionStatus.COMPLETE, verdict, issues, summary);
    }

    private void compareCity(List<ReviewIssue> issues,
                             ItineraryPlanningResult planningResult,
                             ItineraryReviewContext context,
                             TravelOrderReference order,
                             List<String> proposalIds,
                             String field,
                             String actual,
                             String expected,
                             ItineraryReviewIssueCode code,
                             String message) {
        if (ItineraryReviewSupport.sameCity(actual, expected)) {
            return;
        }
        issues.add(blockingMismatch(planningResult, context, order, proposalIds, field,
                actual, expected, code, message));
    }

    private void compareDate(List<ReviewIssue> issues,
                             ItineraryPlanningResult planningResult,
                             ItineraryReviewContext context,
                             TravelOrderReference order,
                             List<String> proposalIds,
                             String field,
                             LocalDate actual,
                             LocalDate expected,
                             ItineraryReviewIssueCode code,
                             String message) {
        if (Objects.equals(actual, expected)) {
            return;
        }
        issues.add(blockingMismatch(planningResult, context, order, proposalIds, field,
                String.valueOf(actual), String.valueOf(expected), code, message));
    }

    private ReviewIssue blockingMismatch(ItineraryPlanningResult planningResult,
                                         ItineraryReviewContext context,
                                         TravelOrderReference order,
                                         List<String> proposalIds,
                                         String field,
                                         String actual,
                                         String expected,
                                         ItineraryReviewIssueCode code,
                                         String message) {
        ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.PLAN_RESULT,
                planningResult.getPlanId(), field, context.reviewedAt(), actual, expected);
        return new ReviewIssue(code.name(), code, dimension(), ItineraryReviewSeverity.BLOCKING,
                true, true, proposalIds, List.of(evidence),
                message + "：规划值=" + actual + "，差旅单值=" + expected + "，差旅单号=" + order.orderId());
    }

    private void reviewOrderStatus(List<ReviewIssue> issues,
                                   ItineraryReviewContext context,
                                   TravelOrderReference order,
                                   List<String> proposalIds) {
        TravelOrderStatus status = order.status();
        if (status == TravelOrderStatus.APPROVED) {
            return;
        }

        ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.TRAVEL_ORDER,
                order.orderId(), "status", context.reviewedAt(), status.getCode(),
                TravelOrderStatus.APPROVED.getCode());
        if (status == TravelOrderStatus.DRAFT || status == TravelOrderStatus.SUBMITTED) {
            ItineraryReviewIssueCode code = ItineraryReviewIssueCode.TRAVEL_ORDER_PENDING_APPROVAL;
            issues.add(new ReviewIssue(code.name(), code, dimension(),
                    ItineraryReviewSeverity.WARNING, false, false, proposalIds, List.of(evidence),
                    "关联差旅单尚未审批通过，当前可以继续比较方案，但不能据此进入正式预订"));
            return;
        }

        ItineraryReviewIssueCode code = ItineraryReviewIssueCode.TRAVEL_ORDER_INACTIVE;
        issues.add(new ReviewIssue(code.name(), code, dimension(),
                ItineraryReviewSeverity.BLOCKING, true, false, proposalIds, List.of(evidence),
                "关联差旅单状态为" + status.getLabel() + "，不能作为当前行程规划依据"));
    }

    private List<String> proposalIds(ItineraryPlanningResult planningResult) {
        if (planningResult.getProposals() == null) {
            return List.of();
        }
        return planningResult.getProposals().stream()
                .filter(Objects::nonNull)
                .map(ItineraryPlanningResult.Proposal::proposalId)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private ItineraryDimensionReviewResult result(ItineraryReviewDimensionStatus status,
                                                  ItineraryReviewVerdict verdict,
                                                  List<ReviewIssue> issues,
                                                  String summary) {
        List<String> issueIds = issues.stream().map(ReviewIssue::issueId).toList();
        DimensionReview dimensionReview = new DimensionReview(dimension(), status,
                reviewerType(), reviewerVersion(), verdict, issueIds, summary);
        return new ItineraryDimensionReviewResult(dimensionReview, issues);
    }
}
