package com.fons.cloud.ai.trip.common.response;

/**
 * 行程页面已保存的客户端通知数据。不包含HTML内容或OSS对象路径。
 *
 * @param planId 可用于读取页面的规划标识
 * @param reviewId 页面对应的审核标识
 * @param recommendedProposalId 推荐方案标识
 */
public record ItineraryPlanPageReady(String planId, String reviewId, String recommendedProposalId) {
}
