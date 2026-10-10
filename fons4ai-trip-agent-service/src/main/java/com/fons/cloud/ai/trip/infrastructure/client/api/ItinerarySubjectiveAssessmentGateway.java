package com.fons.cloud.ai.trip.infrastructure.client.api;

import com.fons.cloud.ai.trip.common.dto.ItineraryReviewContext;
import com.fons.cloud.ai.trip.common.dto.ItinerarySubjectiveAssessment;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;

/**
 * 主观审核模型客户端契约，仅向调用方返回结构化发现。
 *
 * @author hongqy
 */
public interface ItinerarySubjectiveAssessmentGateway {

    ItinerarySubjectiveAssessment assess(ItineraryReviewDimension dimension,
                                         ItineraryPlanningResult planningResult,
                                         ItineraryReviewContext context);
}
