package com.fons.cloud.ai.trip.infrastructure.config;

import com.fons.cloud.ai.trip.application.itinerary.reviewer.ItineraryExecutionFeasibilityReviewer;
import com.fons.cloud.ai.trip.application.itinerary.reviewer.ItineraryReviewOrchestrator;
import com.fons.cloud.ai.trip.application.itinerary.reviewer.SubjectiveDimensionReviewer;
import com.fons.cloud.ai.trip.application.itinerary.reviewer.TravelOrderConsistencyReviewer;
import com.fons.cloud.ai.trip.application.itinerary.reviewer.TravelPolicyComplianceReviewer;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.infrastructure.client.api.ItinerarySubjectiveAssessmentGateway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.EnumSet;
import java.util.List;

/**
 * 装配行程审核维度与模型客户端，业务审核规则由各审核器实现。
 *
 * @author hongqy
 */
@Configuration
public class ItineraryReviewConfiguration {

    @Bean
    public ItineraryReviewOrchestrator itineraryReviewOrchestrator(
            ItinerarySubjectiveAssessmentGateway subjectiveAssessmentGateway) {
        ItineraryReviewOrchestrator.ItineraryReviewRuleSet ruleSet = new ItineraryReviewOrchestrator.ItineraryReviewRuleSet(
                EnumSet.of(ItineraryReviewDimension.TRAVEL_ORDER_CONSISTENCY,
                        ItineraryReviewDimension.POLICY_COMPLIANCE,
                        ItineraryReviewDimension.EXECUTION_FEASIBILITY,
                        ItineraryReviewDimension.EXPERIENCE_AND_PREFERENCE,
                        ItineraryReviewDimension.RESILIENCE),
                List.of(new TravelOrderConsistencyReviewer(),
                        new TravelPolicyComplianceReviewer(),
                        new ItineraryExecutionFeasibilityReviewer(),
                        new SubjectiveDimensionReviewer(ItineraryReviewDimension.EXPERIENCE_AND_PREFERENCE, subjectiveAssessmentGateway),
                        new SubjectiveDimensionReviewer(ItineraryReviewDimension.RESILIENCE, subjectiveAssessmentGateway)));
        return new ItineraryReviewOrchestrator(ruleSet);
    }
}
