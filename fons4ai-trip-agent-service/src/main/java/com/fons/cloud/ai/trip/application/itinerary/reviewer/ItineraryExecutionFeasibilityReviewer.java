package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewEvidenceSource;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewIssueCode;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.HotelOption;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Metrics;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Scores;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TransportOption;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TripRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.DimensionReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewEvidence;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewIssue;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 方案执行可行性确定性审核器。
 * 检查代表方案的组成、路线、日期、时间顺序、住宿区间，并独立复算费用和耗时指标。
 * 当前规划结果没有每日活动和市内接驳时间，本审核器不推测活动缓冲或机场、车站通勤时间。
 *
 * @author hongqy
 */
public final class ItineraryExecutionFeasibilityReviewer implements ItineraryDimensionReviewer {

    private static final String REVIEWER_VERSION = "itinerary-execution-feasibility-v1";
    private static final String PLANNING_CURRENCY = "CNY";
    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    @Override
    public ItineraryReviewDimension dimension() {
        return ItineraryReviewDimension.EXECUTION_FEASIBILITY;
    }

    @Override
    public String reviewerVersion() {
        return REVIEWER_VERSION;
    }

    @Override
    public ItineraryDimensionReviewResult review(ItineraryPlanningResult planningResult,
                                                  ItineraryReviewContext context) {
        if (planningResult == null || StringUtils.isBlank(planningResult.getPlanId())
                || planningResult.getUserRequest() == null) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划结果、planId或行程请求缺失，无法执行方案可行性审核");
        }

        TripRequest request = planningResult.getUserRequest();
        if (StringUtils.isAnyBlank(request.origin(), request.destination())
                || request.departureDate() == null || request.returnDate() == null) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划行程请求缺少城市或日期，无法执行方案可行性审核");
        }

        List<Proposal> proposals = planningResult.getProposals();
        if (proposals == null || proposals.isEmpty()
                || proposals.stream().anyMatch(proposal -> proposal == null
                || StringUtils.isBlank(proposal.proposalId()))) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划结果缺少可审核的代表方案或proposalId");
        }
        if (proposals.stream().map(Proposal::proposalId).distinct().count() != proposals.size()) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划结果包含重复proposalId，无法形成稳定审核引用");
        }

        List<ReviewIssue> issues = new ArrayList<>();
        List<String> allProposalIds = proposals.stream().map(Proposal::proposalId).toList();
        if (!request.returnDate().isAfter(request.departureDate())) {
            addPlanIssue(issues, ItineraryReviewIssueCode.TRIP_DATE_RANGE_INVALID,
                    planningResult, allProposalIds, "userRequest.dateRange",
                    request.departureDate() + "→" + request.returnDate(), "返程日期晚于去程日期", true,
                    "规划日期范围无效，当前业务至少需要住宿一晚");
        }
        if (!PLANNING_CURRENCY.equalsIgnoreCase(StringUtils.trimToEmpty(planningResult.getCurrency()))) {
            addPlanIssue(issues, ItineraryReviewIssueCode.PLANNING_CURRENCY_INVALID,
                    planningResult, allProposalIds, "currency", planningResult.getCurrency(),
                    PLANNING_CURRENCY, true, "规划结果币种缺失或不是当前支持的CNY");
        }
        for (Proposal proposal : proposals) {
            reviewProposal(planningResult, request, proposal, issues);
        }
        ItineraryReviewVerdict verdict = ItineraryReviewSupport.verdictOf(issues);
        String summary = issues.isEmpty()
                ? "所有代表方案的路线、日期、时间顺序、住宿区间和计算指标均可执行"
                : "方案执行可行性审核发现" + issues.size() + "个阻断问题";
        return result(ItineraryReviewDimensionStatus.COMPLETE, verdict, issues, summary);
    }

    private void reviewProposal(ItineraryPlanningResult planningResult,
                                TripRequest request,
                                Proposal proposal,
                                List<ReviewIssue> issues) {
        TransportOption outbound = proposal.outbound();
        HotelOption hotel = proposal.hotel();
        TransportOption inbound = proposal.inbound();
        Metrics metrics = proposal.metrics();
        Scores scores = proposal.scores();

        boolean outboundReady = requireComponent(planningResult, proposal, "outbound", outbound, issues);
        boolean hotelReady = requireComponent(planningResult, proposal, "hotel", hotel, issues);
        boolean inboundReady = requireComponent(planningResult, proposal, "inbound", inbound, issues);
        boolean metricsReady = requireComponent(planningResult, proposal, "metrics", metrics, issues);
        reviewScores(planningResult, proposal, scores, issues);

        if (outboundReady) {
            reviewTransport(planningResult, proposal, outbound, "outbound", "去程",
                    request.origin(), request.destination(), request.departureDate(), issues);
        }
        if (inboundReady) {
            reviewTransport(planningResult, proposal, inbound, "inbound", "返程",
                    request.destination(), request.origin(), request.returnDate(), issues);
        }
        if (hotelReady) {
            reviewHotel(planningResult, proposal, hotel, request, issues);
        }
        if (outboundReady && inboundReady
                && outbound.arrivalTime() != null && inbound.departureTime() != null
                && !inbound.departureTime().isAfter(outbound.arrivalTime())) {
            addIssue(issues, ItineraryReviewIssueCode.ROUND_TRIP_TIME_CONFLICT,
                    planningResult, proposal, proposal.proposalId(), "roundTrip.timeOrder",
                    inbound.departureTime().toString(), "晚于" + outbound.arrivalTime(), true,
                    "返程出发时间没有晚于去程到达时间，无法形成有效往返行程");
        }
        if (outboundReady && hotelReady && inboundReady && metricsReady) {
            reviewMetrics(planningResult, proposal, outbound, hotel, inbound, metrics, issues);
        }
    }

    private boolean requireComponent(ItineraryPlanningResult planningResult,
                                     Proposal proposal,
                                     String component,
                                     Object value,
                                     List<ReviewIssue> issues) {
        if (value != null) {
            return true;
        }
        addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_COMPONENT_MISSING,
                planningResult, proposal, proposal.proposalId(), component,
                "null", "非空", true, "代表方案缺少" + component + "数据");
        return false;
    }

    private void reviewTransport(ItineraryPlanningResult planningResult,
                                 Proposal proposal,
                                 TransportOption transport,
                                 String fieldPrefix,
                                 String direction,
                                 String expectedOrigin,
                                 String expectedDestination,
                                 LocalDate expectedDate,
                                 List<ReviewIssue> issues) {
        String referenceId = StringUtils.defaultIfBlank(transport.candidateId(), proposal.proposalId());
        if (StringUtils.isBlank(transport.candidateId())) {
            addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_COMPONENT_MISSING,
                    planningResult, proposal, referenceId, fieldPrefix + ".candidateId",
                    transport.candidateId(), "非空", true,
                    direction + "交通缺少可稳定引用的候选标识");
        }
        if (transport.type() != BookingType.FLIGHT && transport.type() != BookingType.TRAIN) {
            addIssue(issues, ItineraryReviewIssueCode.TRANSPORT_TYPE_INVALID,
                    planningResult, proposal, referenceId, fieldPrefix + ".type",
                    String.valueOf(transport.type()), "FLIGHT或TRAIN", true,
                    direction + "交通类型不是机票或火车票");
        }
        if (!ItineraryReviewSupport.sameCity(transport.origin(), expectedOrigin)
                || !ItineraryReviewSupport.sameCity(transport.destination(), expectedDestination)) {
            addIssue(issues, ItineraryReviewIssueCode.TRANSPORT_ROUTE_MISMATCH,
                    planningResult, proposal, referenceId, fieldPrefix + ".route",
                    transport.origin() + "→" + transport.destination(),
                    expectedOrigin + "→" + expectedDestination, true,
                    direction + "交通路线与规划行程不一致");
        }
        if (transport.departureTime() == null || transport.arrivalTime() == null) {
            addIssue(issues, ItineraryReviewIssueCode.TRANSPORT_TIME_INVALID,
                    planningResult, proposal, referenceId, fieldPrefix + ".time",
                    String.valueOf(transport.departureTime()) + "→" + transport.arrivalTime(),
                    "完整且到达时间晚于出发时间", true, direction + "交通时间缺失");
            return;
        }
        if (!expectedDate.equals(transport.departureTime().toLocalDate())) {
            addIssue(issues, ItineraryReviewIssueCode.TRANSPORT_DEPARTURE_DATE_MISMATCH,
                    planningResult, proposal, referenceId, fieldPrefix + ".departureDate",
                    transport.departureTime().toLocalDate().toString(), expectedDate.toString(), true,
                    direction + "交通出发日期与规划日期不一致");
        }
        if (!transport.arrivalTime().isAfter(transport.departureTime())) {
            addIssue(issues, ItineraryReviewIssueCode.TRANSPORT_TIME_INVALID,
                    planningResult, proposal, referenceId, fieldPrefix + ".timeOrder",
                    transport.departureTime() + "→" + transport.arrivalTime(),
                    "到达时间晚于出发时间", true, direction + "交通到达时间不晚于出发时间");
            return;
        }
        long actualMinutes = Duration.between(transport.departureTime(), transport.arrivalTime()).toMinutes();
        if (transport.transitMinutes() <= 0 || transport.transitMinutes() != actualMinutes) {
            addIssue(issues, ItineraryReviewIssueCode.TRANSPORT_DURATION_MISMATCH,
                    planningResult, proposal, referenceId, fieldPrefix + ".transitMinutes",
                    String.valueOf(transport.transitMinutes()), String.valueOf(actualMinutes), true,
                    direction + "交通耗时与出发到达时间不一致");
        }
        if (transport.price() == null || transport.price().compareTo(BigDecimal.ZERO) <= 0) {
            addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_COMPONENT_MISSING,
                    planningResult, proposal, referenceId, fieldPrefix + ".price",
                    String.valueOf(transport.price()), "大于0", true,
                    direction + "交通缺少有效价格");
        }
    }

    private void reviewHotel(ItineraryPlanningResult planningResult,
                             Proposal proposal,
                             HotelOption hotel,
                             TripRequest request,
                             List<ReviewIssue> issues) {
        String referenceId = StringUtils.defaultIfBlank(hotel.candidateId(), proposal.proposalId());
        if (StringUtils.isAnyBlank(hotel.candidateId(), hotel.name(), hotel.city(), hotel.roomType())) {
            addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_COMPONENT_MISSING,
                    planningResult, proposal, referenceId, "hotel.identity",
                    hotel.candidateId() + "/" + hotel.name() + "/" + hotel.city() + "/" + hotel.roomType(),
                    "完整酒店、城市、房型和报价标识", true, "住宿方案缺少酒店、城市或房型标识");
        }
        if (StringUtils.isNotBlank(hotel.city())
                && !ItineraryReviewSupport.sameCity(hotel.city(), request.destination())) {
            addIssue(issues, ItineraryReviewIssueCode.HOTEL_CITY_MISMATCH,
                    planningResult, proposal, referenceId, "hotel.city",
                    hotel.city(), request.destination(), true,
                    "酒店所在城市与规划目的城市不一致");
        }
        long expectedNights = ChronoUnit.DAYS.between(request.departureDate(), request.returnDate());
        if (!request.departureDate().equals(hotel.checkInDate())
                || !request.returnDate().equals(hotel.checkOutDate())
                || hotel.nights() != expectedNights) {
            String actual = hotel.checkInDate() + "→" + hotel.checkOutDate() + "/" + hotel.nights() + "晚";
            String expected = request.departureDate() + "→" + request.returnDate() + "/" + expectedNights + "晚";
            addIssue(issues, ItineraryReviewIssueCode.HOTEL_STAY_MISMATCH,
                    planningResult, proposal, referenceId, "hotel.stay", actual, expected, true,
                    "住宿日期或晚数与规划行程不一致");
        }
        if (hotel.pricePerNight() == null || hotel.pricePerNight().compareTo(BigDecimal.ZERO) <= 0) {
            addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_COMPONENT_MISSING,
                    planningResult, proposal, referenceId, "hotel.pricePerNight",
                    String.valueOf(hotel.pricePerNight()), "大于0", true,
                    "住宿方案缺少有效的每晚价格");
        }
    }

    private void reviewScores(ItineraryPlanningResult planningResult,
                              Proposal proposal,
                              Scores scores,
                              List<ReviewIssue> issues) {
        if (scores != null && scores.overall() != null
                && scores.overall().compareTo(BigDecimal.ZERO) >= 0
                && scores.overall().compareTo(BigDecimal.valueOf(100)) <= 0) {
            return;
        }
        addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_SCORE_INVALID,
                planningResult, proposal, proposal.proposalId(), "scores.overall",
                scores == null ? "null" : String.valueOf(scores.overall()), "0至100", true,
                "方案缺少有效综合分，无法参与可靠推荐排序");
    }

    private void reviewMetrics(ItineraryPlanningResult planningResult,
                               Proposal proposal,
                               TransportOption outbound,
                               HotelOption hotel,
                               TransportOption inbound,
                               Metrics metrics,
                               List<ReviewIssue> issues) {
        if (!canRecalculate(outbound, hotel, inbound, metrics)) {
            addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_METRICS_MISMATCH,
                    planningResult, proposal, proposal.proposalId(), "metrics",
                    "存在空值或无效值", "费用与时间指标完整", true,
                    "方案费用或时间指标不完整，无法验证计算结果");
            return;
        }

        BigDecimal hotelTotalPrice = hotel.pricePerNight().multiply(BigDecimal.valueOf(hotel.nights()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPrice = outbound.price().add(hotelTotalPrice).add(inbound.price())
                .setScale(2, RoundingMode.HALF_UP);
        long totalTransitMinutes;
        try {
            totalTransitMinutes = Math.addExact(outbound.transitMinutes(), inbound.transitMinutes());
        } catch (ArithmeticException e) {
            addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_METRICS_MISMATCH,
                    planningResult, proposal, proposal.proposalId(), "metrics.totalTransitMinutes",
                    "数值溢出", "有效分钟数", true, "方案交通总耗时计算溢出");
            return;
        }
        long stayMinutes = Duration.between(outbound.arrivalTime(), inbound.departureTime()).toMinutes();
        BigDecimal stayHours = BigDecimal.valueOf(stayMinutes)
                .divide(MINUTES_PER_HOUR, 1, RoundingMode.HALF_UP);

        compareMetric(issues, planningResult, proposal, "metrics.hotelTotalPrice",
                metrics.hotelTotalPrice(), hotelTotalPrice);
        compareMetric(issues, planningResult, proposal, "metrics.totalPrice",
                metrics.totalPrice(), totalPrice);
        if (metrics.totalTransitMinutes() != totalTransitMinutes) {
            addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_METRICS_MISMATCH,
                    planningResult, proposal, proposal.proposalId(), "metrics.totalTransitMinutes",
                    String.valueOf(metrics.totalTransitMinutes()), String.valueOf(totalTransitMinutes), true,
                    "方案交通总耗时计算结果不一致");
        }
        compareMetric(issues, planningResult, proposal, "metrics.stayHours",
                metrics.stayHours(), stayHours);
    }

    private boolean canRecalculate(TransportOption outbound,
                                   HotelOption hotel,
                                   TransportOption inbound,
                                   Metrics metrics) {
        return outbound.price() != null && inbound.price() != null && hotel.pricePerNight() != null
                && outbound.arrivalTime() != null && inbound.departureTime() != null
                && metrics.hotelTotalPrice() != null && metrics.totalPrice() != null
                && metrics.stayHours() != null;
    }

    private void compareMetric(List<ReviewIssue> issues,
                               ItineraryPlanningResult planningResult,
                               Proposal proposal,
                               String field,
                               BigDecimal actual,
                               BigDecimal expected) {
        if (actual.compareTo(expected) == 0) {
            return;
        }
        addIssue(issues, ItineraryReviewIssueCode.PROPOSAL_METRICS_MISMATCH,
                planningResult, proposal, proposal.proposalId(), field,
                actual.toPlainString(), expected.toPlainString(), true,
                "方案计算指标不一致：" + field);
    }

    private void addIssue(List<ReviewIssue> issues,
                          ItineraryReviewIssueCode code,
                          ItineraryPlanningResult planningResult,
                          Proposal proposal,
                          String referenceId,
                          String field,
                          String actual,
                          String expected,
                          boolean repairable,
                          String message) {
        String issueId = code.name() + ":" + proposal.proposalId() + ":" + field;
        ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.PLAN_RESULT,
                StringUtils.defaultIfBlank(referenceId, proposal.proposalId()), field,
                planningResult.getGeneratedAt(), actual, expected);
        issues.add(new ReviewIssue(issueId, code, dimension(), ItineraryReviewSeverity.BLOCKING,
                true, repairable, List.of(proposal.proposalId()), List.of(evidence), message));
    }

    private void addPlanIssue(List<ReviewIssue> issues,
                              ItineraryReviewIssueCode code,
                              ItineraryPlanningResult planningResult,
                              List<String> proposalIds,
                              String field,
                              Object actual,
                              String expected,
                              boolean repairable,
                              String message) {
        ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.PLAN_RESULT,
                planningResult.getPlanId(), field, planningResult.getGeneratedAt(),
                String.valueOf(actual), expected);
        issues.add(new ReviewIssue(code.name(), code, dimension(), ItineraryReviewSeverity.BLOCKING,
                true, repairable, proposalIds, List.of(evidence), message));
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
