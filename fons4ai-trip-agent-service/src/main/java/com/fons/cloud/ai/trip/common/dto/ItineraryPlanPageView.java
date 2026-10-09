package com.fons.cloud.ai.trip.common.dto;

import java.util.List;

/**
 * 独立行程HTML页面的展示契约。
 * 推荐方案单独置顶；其余方案保留规划顺序和各自的审核状态。
 *
 * @author hongqy
 */
public record ItineraryPlanPageView(String planId, String reviewId, String route,
                                    String dateRange, String travelOrderId,
                                    String reviewSummary, String weatherSummary,
                                    String generatedAt, String reviewedAt,
                                    ItineraryProposalPageView recommended,
                                    List<ItineraryProposalPageView> alternatives) {
}
