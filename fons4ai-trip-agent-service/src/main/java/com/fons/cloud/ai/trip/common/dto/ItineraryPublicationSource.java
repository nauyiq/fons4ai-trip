package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;

/**
 * 已通过发布资格校验的规划与审核数据，供后续方案渲染和保存使用。
 * 不表示页面已经发布，也不表示用户已选择或预订方案。
 *
 * @author hongqy
 */
public record ItineraryPublicationSource(ItineraryPlanningResult planningResult,
                                         ItineraryReviewResult reviewResult,
                                         ItineraryPlanningResult.Proposal recommendedProposal) {
}
