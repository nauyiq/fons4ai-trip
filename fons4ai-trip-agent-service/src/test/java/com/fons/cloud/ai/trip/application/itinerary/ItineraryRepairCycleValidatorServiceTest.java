package com.fons.cloud.ai.trip.application.itinerary;

import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.application.itinerary.validator.ItineraryRepairCycleValidatorService;
import com.fons.cloud.ai.trip.common.constants.ItineraryRepairStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.ItineraryRepairCycle;
import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TripRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryRepairCycleRepository;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItineraryRepairCycleValidatorServiceTest {

    private final CandidateOwner owner = new CandidateOwner("USER", "CONVERSATION");
    private final AtomicReference<ItineraryRepairCycle> stored = new AtomicReference<>();
    private ItineraryRepairCycleValidatorService service;
    private ItineraryPlanRequest request;

    @BeforeEach
    void setUp() {
        ItineraryRepairCycleRepository repository = new ItineraryRepairCycleRepository(null) {
            @Override
            public ItineraryRepairCycle find(CandidateOwner owner) {
                return stored.get();
            }

            @Override
            public void save(CandidateOwner owner, ItineraryRepairCycle cycle) {
                stored.set(cycle);
            }
        };
        service = new ItineraryRepairCycleValidatorService(repository);
        LocalDate departure = LocalDate.now().plusDays(7);
        request = ItineraryPlanRequest.builder().origin("上海").destination("杭州")
                .departureDate(departure).returnDate(departure.plusDays(2)).build();
    }

    @Test
    void stopsAfterTwoSuccessfulRepairsAndDoesNotAllowAnotherPlanInSameRun() {
        plan("PLAN-0", "RUN-1");
        review("PLAN-0", "RUN-1", ItineraryReviewNextAction.REPLAN);
        plan("PLAN-1", "RUN-1");
        review("PLAN-1", "RUN-1", ItineraryReviewNextAction.REPLAN);
        plan("PLAN-2", "RUN-1");

        ItineraryReviewResult finalReview = review("PLAN-2", "RUN-1", ItineraryReviewNextAction.REPLAN);

        assertEquals(ItineraryReviewNextAction.STOP_NO_FEASIBLE_PROPOSAL, finalReview.getNextAction());
        assertEquals(2, stored.get().replanCount());
        assertEquals(ItineraryRepairStatus.CLOSED, stored.get().status());
        assertThrows(BusinessRuntimeException.class, () -> service.beforePlan(owner, request, "RUN-1"));
    }

    @Test
    void proceedingWithWarningsClosesCycleAndNextUserRunMayStartFresh() {
        plan("PLAN-0", "RUN-1");
        ItineraryReviewResult result = review("PLAN-0", "RUN-1", ItineraryReviewNextAction.PROCEED);

        assertEquals(ItineraryReviewNextAction.PROCEED, result.getNextAction());
        assertEquals(0, stored.get().replanCount());
        assertThrows(BusinessRuntimeException.class, () -> service.beforePlan(owner, request, "RUN-1"));
        service.beforePlan(owner, request, "RUN-2");
        service.afterPlan(owner, request, "RUN-2", planResult("PLAN-NEW"));
        assertEquals(0, stored.get().replanCount());
    }

    @Test
    void reviewMayRetryOnceWithoutConsumingReplanCount() {
        plan("PLAN-0", "RUN-1");
        review("PLAN-0", "RUN-1", ItineraryReviewNextAction.RETRY_REVIEW);

        assertEquals(ItineraryRepairStatus.RETRY_REVIEW, stored.get().status());
        assertEquals(0, stored.get().replanCount());
        ItineraryReviewResult second = review("PLAN-0", "RUN-1", ItineraryReviewNextAction.RETRY_REVIEW);
        assertEquals(ItineraryReviewNextAction.STOP_REVIEW_INCOMPLETE, second.getNextAction());
        assertEquals(ItineraryRepairStatus.CLOSED, stored.get().status());
    }

    @Test
    void userInputPreservesCountAndCannotBeSkippedInSameRun() {
        plan("PLAN-0", "RUN-1");
        review("PLAN-0", "RUN-1", ItineraryReviewNextAction.REQUEST_USER_INPUT);

        assertThrows(BusinessRuntimeException.class, () -> service.beforePlan(owner, request, "RUN-1"));
        service.beforePlan(owner, request, "RUN-2");
        service.afterPlan(owner, request, "RUN-2", planResult("PLAN-1"));
        assertEquals(1, stored.get().replanCount());
    }

    @Test
    void oldPlanCannotReplaceCurrentReviewResult() {
        plan("PLAN-0", "RUN-1");
        review("PLAN-0", "RUN-1", ItineraryReviewNextAction.REPLAN);
        plan("PLAN-1", "RUN-1");

        assertThrows(BusinessRuntimeException.class,
                () -> service.beforeReview(owner, "PLAN-0", "RUN-1"));
    }

    @Test
    void storedCycleCanRoundTripThroughRepositoryJsonFormat() {
        plan("PLAN-0", "RUN-1");

        ItineraryRepairCycle restored = JSON.parseObject(
                JSON.toJSONString(stored.get()), ItineraryRepairCycle.class);

        assertEquals(stored.get(), restored);
    }

    private void plan(String planId, String runId) {
        service.beforePlan(owner, request, runId);
        service.afterPlan(owner, request, runId, planResult(planId));
    }

    private ItineraryReviewResult review(String planId, String runId, ItineraryReviewNextAction action) {
        service.beforeReview(owner, planId, runId);
        ItineraryReviewResult result = ItineraryReviewResult.builder()
                .planId(planId).nextAction(action).summary("审核摘要").build();
        ItineraryRepairCycle resolved = service.resolveAfterReview(owner, planResult(planId), runId, result);
        service.saveResolvedReview(owner, resolved);
        return result;
    }

    private ItineraryPlanningResult planResult(String planId) {
        return ItineraryPlanningResult.builder().planId(planId)
                .userRequest(new TripRequest(request.getOrigin(), request.getDestination(),
                        request.getDepartureDate(), request.getReturnDate()))
                .build();
    }
}
