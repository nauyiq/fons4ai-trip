package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;

import java.time.OffsetDateTime;

/**
 * 差旅单最新已发布方案摘要，不包含内部OSS对象路径。
 *
 * @param orderId 差旅单标识
 * @param orderStatus 差旅单当前状态
 * @param statusLabel 差旅单当前状态说明
 * @param planId 已发布规划标识
 * @param generatedAt 规划生成时间
 * @param reviewId 对应的审核标识
 * @param reviewedAt 审核时间
 * @param verdict 审核业务结论
 * @param nextAction 审核后的下一步动作
 * @param recommendedProposalId 已推荐的方案标识
 * @param summary 审核摘要
 */
public record PublishedItinerarySummary(String orderId, String orderStatus, String statusLabel,
                                        String planId, OffsetDateTime generatedAt,
                                        String reviewId, OffsetDateTime reviewedAt,
                                        ItineraryReviewVerdict verdict, ItineraryReviewNextAction nextAction,
                                        String recommendedProposalId, String summary) {
}
