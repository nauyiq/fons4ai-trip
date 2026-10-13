package com.fons.cloud.ai.trip.application.itinerary;

import com.fons.cloud.ai.trip.application.itinerary.validator.ItineraryPublicationEligibilityValidatorService;
import com.fons.cloud.ai.trip.common.constants.ItineraryRepairStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.ItineraryRepairCycle;
import com.fons.cloud.ai.trip.common.dto.ItineraryScope;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TripRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryProposalReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanRepository;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryRepairCycleRepository;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryReviewRepository;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItineraryPublicationEligibilityValidatorServiceTest {

    private final CandidateOwner owner = new CandidateOwner("USER", "CONVERSATION");
    private final AtomicReference<ItineraryPlanningResult> plan = new AtomicReference<>();
    private final AtomicReference<ItineraryReviewResult> review = new AtomicReference<>();
    private final AtomicReference<ItineraryRepairCycle> cycle = new AtomicReference<>();
    private ItineraryPublicationEligibilityValidatorService service;

    @BeforeEach
    void setUp() {
        LocalDate departure = LocalDate.now().plusDays(7);
        TripRequest request = new TripRequest("上海", "杭州", departure, departure.plusDays(2));
        plan.set(ItineraryPlanningResult.builder().planId("PLAN-1").userRequest(request)
                .proposals(List.of(new Proposal("PROPOSAL-1", null, null, null, null,
                        null, null, null, null, null, null)))
                .build());
        review.set(ItineraryReviewResult.builder().reviewId("REVIEW-1").planId("PLAN-1")
                .executionStatus(ItineraryReviewExecutionStatus.COMPLETE)
                .verdict(ItineraryReviewVerdict.WARNING)
                .nextAction(ItineraryReviewNextAction.PROCEED)
                .recommendedProposalId("PROPOSAL-1")
                .proposalReviews(List.of(new ItineraryProposalReview("PROPOSAL-1",
                        ItineraryReviewVerdict.WARNING, true, List.of())))
                .build());
        cycle.set(new ItineraryRepairCycle(ItineraryScope.from(plan.get()), "PLAN-1", "RUN-1",
                0, 0, ItineraryRepairStatus.CLOSED));

        ItineraryPlanRepository planRepository = new ItineraryPlanRepository(null) {
            @Override
            public ItineraryPlanningResult findById(CandidateOwner owner, String planId) {
                return planId.equals(plan.get().getPlanId()) ? plan.get() : null;
            }
        };
        ItineraryReviewRepository reviewRepository = new ItineraryReviewRepository(null) {
            @Override
            public ItineraryReviewResult findLatest(CandidateOwner owner, String planId) {
                return planId.equals(review.get().getPlanId()) ? review.get() : null;
            }
        };
        ItineraryRepairCycleRepository cycleRepository = new ItineraryRepairCycleRepository(null) {
            @Override
            public ItineraryRepairCycle find(CandidateOwner owner) {
                return cycle.get();
            }
        };
        service = new ItineraryPublicationEligibilityValidatorService(planRepository, reviewRepository, cycleRepository);
    }

    @Test
    void warningWithEligibleRecommendationMayBePublished() {
        assertEquals("PROPOSAL-1", service.requirePublishable(owner, "PLAN-1")
                .recommendedProposal().proposalId());
    }

    @Test
    void closedCycleWithStopActionCannotBePublished() {
        review.get().setNextAction(ItineraryReviewNextAction.STOP_NO_FEASIBLE_PROPOSAL);
        assertThrows(BusinessRuntimeException.class,
                () -> service.requirePublishable(owner, "PLAN-1"));
    }

    @Test
    void storedReviewWithoutCommittedClosedCycleCannotBePublished() {
        cycle.set(new ItineraryRepairCycle(ItineraryScope.from(plan.get()), "PLAN-1", "RUN-1",
                0, 0, ItineraryRepairStatus.AWAITING_REVIEW));
        assertThrows(BusinessRuntimeException.class,
                () -> service.requirePublishable(owner, "PLAN-1"));
    }

    @Test
    void oldPlanCannotBePublishedAfterNewPlanBecomesCurrent() {
        cycle.set(new ItineraryRepairCycle(ItineraryScope.from(plan.get()), "PLAN-2", "RUN-1",
                1, 0, ItineraryRepairStatus.CLOSED));
        assertThrows(BusinessRuntimeException.class,
                () -> service.requirePublishable(owner, "PLAN-1"));
    }

    @Test
    void partialReviewCannotBePublishedEvenWithProceedAction() {
        review.get().setExecutionStatus(ItineraryReviewExecutionStatus.PARTIAL);
        assertThrows(BusinessRuntimeException.class,
                () -> service.requirePublishable(owner, "PLAN-1"));
    }
}
