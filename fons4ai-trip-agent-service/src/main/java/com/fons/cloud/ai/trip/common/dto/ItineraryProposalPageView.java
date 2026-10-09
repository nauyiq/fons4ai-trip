package com.fons.cloud.ai.trip.common.dto;

import java.util.List;

/**
 * 单套代表方案的页面展示数据；审核结论与规划评分保持独立。
 *
 * @author hongqy
 */
public record ItineraryProposalPageView(String proposalId, String verdictLabel, String verdictStyle,
                                        boolean eligibleForRecommendation, List<String> tags,
                                        String totalPrice, String overallScore, String transitDuration,
                                        List<ItineraryTravelItemView> travelItems, List<String> reviewIssues,
                                        List<String> policyViolations, List<String> warnings,
                                        List<String> experienceFlags) {
}
