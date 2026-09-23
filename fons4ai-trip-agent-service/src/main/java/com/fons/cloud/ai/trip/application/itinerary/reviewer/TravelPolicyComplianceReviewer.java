package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewEvidenceSource;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewIssueCode;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.constants.TravelCabinClass;
import com.fons.cloud.ai.trip.common.dto.TravelPolicy;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.HotelOption;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TransportOption;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.DimensionReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewEvidence;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewIssue;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 差旅政策合规确定性审核器。
 * 基于规划结果中保存的政策和代表方案快照，检查酒店限额、酒店星级、交通舱位、
 * 审批金额阈值和提前预订天数。缺少必要字段时标记为 PARTIAL，不把未知事实判断为合规。
 *
 * @author hongqy
 */
public final class TravelPolicyComplianceReviewer implements ItineraryDimensionReviewer {

    private static final String REVIEWER_VERSION = "travel-policy-compliance-v1";

    @Override
    public ItineraryReviewDimension dimension() {
        return ItineraryReviewDimension.POLICY_COMPLIANCE;
    }

    @Override
    public String reviewerVersion() {
        return REVIEWER_VERSION;
    }

    @Override
    public ItineraryDimensionReviewResult review(ItineraryPlanningResult planningResult,
                                                  ItineraryReviewContext context) {
        if (planningResult == null || planningResult.getUserRequest() == null
                || StringUtils.isBlank(planningResult.getPlanId())) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划结果、planId或行程请求缺失，无法执行差旅政策审核");
        }

        TravelPolicy policy = planningResult.getPolicy();
        if (policy == null) {
            ItineraryReviewIssueCode code = ItineraryReviewIssueCode.TRAVEL_POLICY_MISSING;
            ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.PLAN_RESULT,
                    planningResult.getPlanId(), "policy", planningResult.getGeneratedAt(), "null", "有效差旅政策");
            ReviewIssue issue = new ReviewIssue(code.name(), code, dimension(),
                    ItineraryReviewSeverity.BLOCKING, true, true, proposalIds(planningResult),
                    List.of(evidence), "规划结果未保存差旅政策，不能判断方案是否合规");
            return result(ItineraryReviewDimensionStatus.NOT_EVALUATED, null, List.of(issue),
                    "规划结果未保存差旅政策，不能判断方案是否合规");
        }
        if (!validPolicy(policy)) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "差旅政策中的酒店限额、星级上限、审批阈值或提前预订天数无效");
        }

        List<Proposal> proposals = planningResult.getProposals();
        if (proposals == null || proposals.isEmpty()
                || proposals.stream().anyMatch(proposal -> proposal == null
                || StringUtils.isBlank(proposal.proposalId()))) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划结果缺少可审核的代表方案或proposalId");
        }

        String destination = planningResult.getUserRequest().destination();
        if (StringUtils.isBlank(destination) || StringUtils.isBlank(policy.getDestinationCity())) {
            return result(ItineraryReviewDimensionStatus.FAILED, null, List.of(),
                    "规划目的城市或政策适用城市缺失，无法确认政策适用范围");
        }

        List<String> allProposalIds = proposals.stream().map(Proposal::proposalId).toList();
        if (!ItineraryReviewSupport.sameCity(destination, policy.getDestinationCity())) {
            ItineraryReviewIssueCode code = ItineraryReviewIssueCode.TRAVEL_POLICY_DESTINATION_MISMATCH;
            ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.TRAVEL_POLICY,
                    policyReferenceId(policy), "destinationCity", planningResult.getGeneratedAt(),
                    policy.getDestinationCity(), destination);
            ReviewIssue issue = new ReviewIssue(code.name(), code, dimension(),
                    ItineraryReviewSeverity.BLOCKING, true, true, allProposalIds, List.of(evidence),
                    "规划目的城市与政策适用城市不一致，不能使用当前政策得出合规结论");
            return result(ItineraryReviewDimensionStatus.PARTIAL, ItineraryReviewVerdict.BLOCKED,
                    List.of(issue), "差旅政策适用城市不匹配，已停止其余政策规则审核");
        }

        PolicyReviewState state = new PolicyReviewState();
        for (Proposal proposal : proposals) {
            reviewHotel(policy, planningResult, proposal, state);
            reviewTransport(policy, planningResult, proposal, proposal.outbound(), "outbound", "去程", state);
            reviewTransport(policy, planningResult, proposal, proposal.inbound(), "inbound", "返程", state);
            reviewApprovalThreshold(policy, planningResult, proposal, state);
        }
        reviewAdvanceBookingDays(policy, planningResult, context, allProposalIds, state);

        ItineraryReviewVerdict verdict = ItineraryReviewSupport.verdictOf(state.issues);
        ItineraryReviewDimensionStatus status = state.skippedRuleCount == 0
                ? ItineraryReviewDimensionStatus.COMPLETE : ItineraryReviewDimensionStatus.PARTIAL;
        String summary = summaryOf(state, verdict);
        return result(status, verdict, state.issues, summary);
    }

    private boolean validPolicy(TravelPolicy policy) {
        return StringUtils.isNoneBlank(policy.getFlightClass(), policy.getTrainSeatClass())
                && Double.isFinite(policy.getHotelLimit()) && policy.getHotelLimit() > 0
                && policy.getHotelStarLimit() >= 0 && policy.getHotelStarLimit() <= 5
                && Double.isFinite(policy.getApprovalThreshold()) && policy.getApprovalThreshold() >= 0
                && policy.getAdvanceBookingDays() >= 0;
    }

    private void reviewHotel(TravelPolicy policy,
                             ItineraryPlanningResult planningResult,
                             Proposal proposal,
                             PolicyReviewState state) {
        HotelOption hotel = proposal.hotel();
        if (hotel == null || hotel.pricePerNight() == null) {
            addMissingEvidence(planningResult, proposal, "hotel.pricePerNight",
                    "有效酒店每晚价格", state);
            return;
        }

        BigDecimal hotelLimit = BigDecimal.valueOf(policy.getHotelLimit());
        if (hotel.pricePerNight().compareTo(hotelLimit) > 0) {
            ItineraryReviewIssueCode code = ItineraryReviewIssueCode.HOTEL_RATE_LIMIT_EXCEEDED;
            state.issues.add(proposalIssue(code, planningResult, proposal, hotel.candidateId(),
                    "hotel.pricePerNight", hotel.pricePerNight().toPlainString(), hotelLimit.toPlainString(),
                    ItineraryReviewSeverity.BLOCKING, true, true,
                    "酒店每晚价格超过差旅政策上限"));
        }

        if (policy.getHotelStarLimit() == 0) {
            return;
        }
        if (hotel.starRating() == null) {
            addMissingEvidence(planningResult, proposal, "hotel.starRating",
                    "明确酒店星级", state);
            return;
        }
        if (hotel.starRating() > policy.getHotelStarLimit()) {
            ItineraryReviewIssueCode code = ItineraryReviewIssueCode.HOTEL_STAR_LIMIT_EXCEEDED;
            state.issues.add(proposalIssue(code, planningResult, proposal, hotel.candidateId(),
                    "hotel.starRating", String.valueOf(hotel.starRating()),
                    String.valueOf(policy.getHotelStarLimit()), ItineraryReviewSeverity.BLOCKING,
                    true, true, "酒店星级超过差旅政策上限"));
        }
    }

    private void reviewTransport(TravelPolicy policy,
                                 ItineraryPlanningResult planningResult,
                                 Proposal proposal,
                                 TransportOption transport,
                                 String fieldPrefix,
                                 String direction,
                                 PolicyReviewState state) {
        if (transport == null || transport.type() == null || StringUtils.isBlank(transport.cabinClass())) {
            addMissingEvidence(planningResult, proposal, fieldPrefix + ".cabinClass",
                    direction + "交通类型和舱位或席别", state);
            return;
        }
        String allowed = switch (transport.type()) {
            case FLIGHT -> policy.getFlightClass();
            case TRAIN -> policy.getTrainSeatClass();
            default -> null;
        };
        if (StringUtils.isBlank(allowed)) {
            addMissingEvidence(planningResult, proposal, fieldPrefix + ".policyCabinClass",
                    direction + "政策舱位或席别标准", state);
            return;
        }
        if (TravelCabinClass.isCompliant(transport.type(), transport.cabinClass(), allowed)) {
            return;
        }

        ItineraryReviewIssueCode code = ItineraryReviewIssueCode.TRANSPORT_CABIN_LIMIT_EXCEEDED;
        state.issues.add(proposalIssue(code, planningResult, proposal, transport.candidateId(),
                fieldPrefix + ".cabinClass", transport.cabinClass(), allowed,
                ItineraryReviewSeverity.BLOCKING, true, true,
                direction + (transport.type() == BookingType.FLIGHT ? "机票舱位" : "火车席别")
                        + "超过差旅政策允许范围"));
    }

    private void reviewApprovalThreshold(TravelPolicy policy,
                                         ItineraryPlanningResult planningResult,
                                         Proposal proposal,
                                         PolicyReviewState state) {
        if (policy.getApprovalThreshold() <= 0) {
            return;
        }
        if (proposal.metrics() == null || proposal.metrics().totalPrice() == null) {
            addMissingEvidence(planningResult, proposal, "metrics.totalPrice",
                    "有效方案总价", state);
            return;
        }
        BigDecimal threshold = BigDecimal.valueOf(policy.getApprovalThreshold());
        if (proposal.metrics().totalPrice().compareTo(threshold) <= 0) {
            return;
        }

        ItineraryReviewIssueCode code = ItineraryReviewIssueCode.APPROVAL_THRESHOLD_EXCEEDED;
        state.issues.add(proposalIssue(code, planningResult, proposal, proposal.proposalId(),
                "metrics.totalPrice", proposal.metrics().totalPrice().toPlainString(), threshold.toPlainString(),
                ItineraryReviewSeverity.WARNING, false, true,
                "方案总价超过政策审批阈值，需要履行相应审批流程"));
    }

    private void reviewAdvanceBookingDays(TravelPolicy policy,
                                          ItineraryPlanningResult planningResult,
                                          ItineraryReviewContext context,
                                          List<String> proposalIds,
                                          PolicyReviewState state) {
        if (policy.getAdvanceBookingDays() <= 0) {
            return;
        }
        LocalDate departureDate = planningResult.getUserRequest().departureDate();
        if (departureDate == null) {
            state.skippedRuleCount++;
            return;
        }
        long actualDays = ChronoUnit.DAYS.between(context.businessDate(), departureDate);
        if (actualDays >= policy.getAdvanceBookingDays()) {
            return;
        }

        ItineraryReviewIssueCode code = ItineraryReviewIssueCode.ADVANCE_BOOKING_DAYS_INSUFFICIENT;
        ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.TRAVEL_POLICY,
                policyReferenceId(policy), "advanceBookingDays", context.reviewedAt(),
                String.valueOf(actualDays), String.valueOf(policy.getAdvanceBookingDays()));
        state.issues.add(new ReviewIssue(code.name(), code, dimension(), ItineraryReviewSeverity.WARNING,
                false, false, proposalIds, List.of(evidence),
                "距离出发还有" + actualDays + "天，少于政策要求的提前"
                        + policy.getAdvanceBookingDays() + "天"));
    }

    private ReviewIssue proposalIssue(ItineraryReviewIssueCode code,
                                      ItineraryPlanningResult planningResult,
                                      Proposal proposal,
                                      String referenceId,
                                      String field,
                                      String actual,
                                      String expected,
                                      ItineraryReviewSeverity severity,
                                      boolean hardConstraint,
                                      boolean repairable,
                                      String message) {
        String issueId = code.name() + ":" + proposal.proposalId() + ":" + field;
        ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.PLAN_RESULT,
                StringUtils.defaultIfBlank(referenceId, proposal.proposalId()), field,
                planningResult.getGeneratedAt(), actual, expected);
        return new ReviewIssue(issueId, code, dimension(), severity, hardConstraint, repairable,
                List.of(proposal.proposalId()), List.of(evidence), message);
    }

    private void addMissingEvidence(ItineraryPlanningResult planningResult,
                                    Proposal proposal,
                                    String field,
                                    String expected,
                                    PolicyReviewState state) {
        ItineraryReviewIssueCode code = ItineraryReviewIssueCode.POLICY_EVIDENCE_MISSING;
        ReviewEvidence evidence = new ReviewEvidence(ItineraryReviewEvidenceSource.PLAN_RESULT,
                proposal.proposalId(), field, planningResult.getGeneratedAt(), "null", expected);
        String issueId = code.name() + ":" + proposal.proposalId() + ":" + field;
        state.issues.add(new ReviewIssue(issueId, code, dimension(), ItineraryReviewSeverity.BLOCKING,
                true, true, List.of(proposal.proposalId()), List.of(evidence),
                "方案缺少政策审核所需数据：" + field));
        state.skippedRuleCount++;
    }

    private List<String> proposalIds(ItineraryPlanningResult planningResult) {
        if (planningResult.getProposals() == null) {
            return List.of();
        }
        return planningResult.getProposals().stream()
                .filter(java.util.Objects::nonNull)
                .map(Proposal::proposalId)
                .filter(StringUtils::isNotBlank)
                .toList();
    }

    private String policyReferenceId(TravelPolicy policy) {
        return StringUtils.defaultIfBlank(policy.getPolicyRuleId(), policy.getDestinationCity());
    }

    private String summaryOf(PolicyReviewState state, ItineraryReviewVerdict verdict) {
        String summary = switch (verdict) {
            case PASS -> "所有可评估的代表方案均符合当前差旅政策";
            case WARNING -> "差旅政策审核发现需要审批或关注的事项";
            case BLOCKED -> "差旅政策审核发现阻断性违规方案";
        };
        return state.skippedRuleCount == 0
                ? summary : summary + "；另有" + state.skippedRuleCount + "项规则因数据缺失未完成评估";
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

    private static final class PolicyReviewState {

        private final List<ReviewIssue> issues = new ArrayList<>();
        private int skippedRuleCount;
    }
}
