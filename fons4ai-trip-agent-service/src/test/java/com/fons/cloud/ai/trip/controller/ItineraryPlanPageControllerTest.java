package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.trip.application.itinerary.ItineraryPlanPageApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ItineraryPlanPageControllerTest {

    private final StubAuth auth = new StubAuth();
    private final StubPageService pageService = new StubPageService();
    private final ItineraryPlanPageController controller = new ItineraryPlanPageController(auth, pageService);
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void unauthenticatedRequestDoesNotReadPage() {
        auth.loggedIn = false;

        R<String> result = controller.read("PLAN-1", "CONVERSATION-1", response);

        assertEquals(TripAgentResultCode.LOGIN_EXPIRED.getCode(), result.getCode());
        assertNull(pageService.readOwner);
    }

    @Test
    void authenticatedRequestReturnsPrivateHtml() {
        auth.loggedIn = true;
        auth.userId = "USER-1";
        pageService.html = "<html>行程方案</html>";

        R<String> result = controller.read("PLAN-1", "CONVERSATION-1", response);

        assertEquals(ResultCode.SUCCESS.getCode(), result.getCode());
        assertEquals(pageService.html, result.getData());
        assertEquals(new CandidateOwner("USER-1", "CONVERSATION-1"), pageService.readOwner);
        assertEquals("PLAN-1", pageService.readPlanId);
        assertEquals("no-store", response.getHeader("Cache-Control"));
    }

    @Test
    void unavailableOrUnauthorizedPlanReturnsNotFound() {
        auth.loggedIn = true;
        auth.userId = "USER-1";
        R<String> result = controller.read("PLAN-1", "CONVERSATION-1", response);
        assertEquals(TripAgentResultCode.ITINERARY_PLAN_PAGE_NOT_EXIST.getCode(), result.getCode());

        pageService.reject = true;
        assertThrows(BusinessRuntimeException.class,
                () -> controller.read("PLAN-1", "CONVERSATION-1", response));
    }

    @Test
    void blankIdentifiersReturnParameterError() {
        auth.loggedIn = true;
        auth.userId = "USER-1";

        R<String> result = controller.read(" ", "CONVERSATION-1", response);

        assertEquals(ResultCode.PARAMS_ERROR.getCode(), result.getCode());
        assertNull(pageService.readOwner);
    }

    private static class StubAuth extends SaTokenAuthTemplate {

        private boolean loggedIn;
        private String userId;

        @Override
        public boolean isLogin() {
            return loggedIn;
        }

        @Override
        public String getCurrentLoginIdAsString() {
            return userId;
        }
    }

    private static class StubPageService extends ItineraryPlanPageApplicationService {

        private String html;
        private boolean reject;
        private CandidateOwner readOwner;
        private String readPlanId;

        private StubPageService() {
            super(null, null, null, null);
        }

        @Override
        public String read(CandidateOwner owner, String planId) {
            readOwner = owner;
            readPlanId = planId;
            if (reject) {
                throw BusinessRuntimeException.of(ResultCode.ILLEGAL_REQUEST_LIMITED.getCode(), "方案不可访问");
            }
            return html;
        }
    }
}
