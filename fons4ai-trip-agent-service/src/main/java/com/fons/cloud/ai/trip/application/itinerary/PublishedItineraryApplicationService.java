package com.fons.cloud.ai.trip.application.itinerary;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.common.response.PublishedItinerarySummary;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.entity.TravelOrderItineraryPlan;
import com.fons.cloud.ai.trip.domain.mapper.TravelOrderItineraryPlanMapper;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanPageStore;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.R;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/** 按当前用户和差旅单读取最新已发布的规划快照与页面。 */
@Service
@RequiredArgsConstructor
public class PublishedItineraryApplicationService {

    private final TravelOrderDomainService travelOrderDomainService;
    private final TravelOrderItineraryPlanMapper planMapper;
    private final ItineraryPlanPageStore pageStore;

    /** 返回最新已发布的规划与审核摘要；未关联方案时明确返回不存在。 */
    public R<PublishedItinerarySummary> getSummary(String userId, String orderId) {
        TravelOrder order = travelOrderDomainService.findByOrderIdAndUserId(orderId, userId);
        if (order == null) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST);
        }
        TravelOrderItineraryPlan plan = planMapper.selectById(orderId);
        if (plan == null) {
            return missingPlan();
        }
        PublishedItinerarySummary summary = toSummary(order, plan);
        if (summary == null) {
            return missingPlan();
        }
        return R.ok(summary);
    }

    /** 根据服务端保存的页面标识读取HTML，不向客户端提供OSS对象标识。 */
    public R<String> getHtml(String userId, String orderId) {
        TravelOrder order = travelOrderDomainService.findByOrderIdAndUserId(orderId, userId);
        if (order == null) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST);
        }
        TravelOrderItineraryPlan plan = planMapper.selectById(orderId);
        if (plan == null) {
            return missingPlan();
        }
        PublishedItinerarySummary summary = toSummary(order, plan);
        if (summary == null) {
            return missingPlan();
        }
        return R.ok(pageStore.readPublished(plan.getPlanHtmlUrl(), summary.reviewId()));
    }

    /** 校验两个持久化快照的关联，并确认方案仍对应差旅单的当前行程。 */
    private PublishedItinerarySummary toSummary(TravelOrder order, TravelOrderItineraryPlan published) {
        ItineraryPlanningResult plan;
        ItineraryReviewResult review;
        try {
            plan = JSON.parseObject(published.getPlanSnapshot(), ItineraryPlanningResult.class);
            review = JSON.parseObject(published.getReviewSnapshot(), ItineraryReviewResult.class);
        } catch (JSONException e) {
            throw SystemIntervalException.of("已发布行程方案快照格式无效", e);
        }
        Assert.isTrue(plan != null && review != null && StringUtils.isNotBlank(plan.getPlanId())
                        && plan.getGeneratedAt() != null && review.getReviewedAt() != null && review.getVerdict() != null
                        && StringUtils.equals(plan.getPlanId(), review.getPlanId())
                        && StringUtils.isNotBlank(review.getReviewId())
                        && StringUtils.isNotBlank(review.getRecommendedProposalId())
                        && review.getExecutionStatus() == ItineraryReviewExecutionStatus.COMPLETE
                        && review.getNextAction() == ItineraryReviewNextAction.PROCEED
                        && plan.getSourceTravelOrder() != null
                        && StringUtils.equals(order.getOrderId(), plan.getSourceTravelOrder().orderId()),
                () -> SystemIntervalException.of("已发布行程方案与审核快照不一致"));
        String recommendedId = review.getRecommendedProposalId();
        Assert.isTrue(plan.getProposals() != null && plan.getProposals().stream()
                        .anyMatch(item -> item != null && recommendedId.equals(item.proposalId()))
                        && review.getProposalReviews() != null && review.getProposalReviews().stream()
                        .anyMatch(item -> item != null && recommendedId.equals(item.proposalId()) && item.eligibleForRecommendation()),
                () -> SystemIntervalException.of("已发布行程推荐方案与审核快照不一致"));

        ItineraryPlanningResult.TravelOrderReference source = plan.getSourceTravelOrder();
        // 订单已修改时旧方案不能作为当前方案读取；取消或完成的单据仍可查看历史快照。
        boolean matchesCurrent = StringUtils.isNoneBlank(source.origin(), source.destination(),
                order.getDepartureCity(), order.getDestination(), order.getDepartureDate(), order.getReturnDate())
                && StringUtils.equals(source.origin(), StringUtils.trimToNull(order.getDepartureCity()))
                && StringUtils.equals(source.destination(), StringUtils.trimToNull(order.getDestination()))
                && source.departureDate() != null && source.returnDate() != null
                && StringUtils.equals(source.departureDate().toString(), StringUtils.trimToNull(order.getDepartureDate()))
                && StringUtils.equals(source.returnDate().toString(), StringUtils.trimToNull(order.getReturnDate()));
        if (!matchesCurrent) {
            return null;
        }
        return new PublishedItinerarySummary(order.getOrderId(),
                order.getStatus() == null ? null : order.getStatus().getCode(),
                order.getStatus() == null ? null : order.getStatus().getLabel(),
                plan.getPlanId(), plan.getGeneratedAt(), review.getReviewId(), review.getReviewedAt(),
                review.getVerdict(), review.getNextAction(), review.getRecommendedProposalId(), review.getSummary());
    }

    private <T> R<T> missingPlan() {
        return R.failed(TripAgentResultCode.ITINERARY_PLAN_PAGE_NOT_EXIST.getCode(), "当前差旅单暂无已发布行程方案");
    }
}
