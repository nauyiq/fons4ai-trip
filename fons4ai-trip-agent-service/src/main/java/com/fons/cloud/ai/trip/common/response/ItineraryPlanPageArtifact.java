package com.fons.cloud.ai.trip.common.response;

/**
 * 已渲染并保存到OSS的行程页面定位信息。
 * objectKey仅供服务端定位对象，不表示页面已向用户发送或对象可匿名访问。
 *
 * @param planId 页面对应的规划结果标识
 * @param reviewId 页面对应的审核结果标识
 * @param recommendedProposalId 审核选出的推荐方案标识
 * @param objectKey OSS对象路径
 * @author hongqy
 */
public record ItineraryPlanPageArtifact(String planId, String reviewId,
                                        String recommendedProposalId, String objectKey) {
}
