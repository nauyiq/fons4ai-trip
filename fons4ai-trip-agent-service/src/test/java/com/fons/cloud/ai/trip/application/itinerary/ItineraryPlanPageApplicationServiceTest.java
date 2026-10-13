package com.fons.cloud.ai.trip.application.itinerary;

import com.fons.cloud.ai.trip.application.itinerary.validator.ItineraryPublicationEligibilityValidatorService;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.ItineraryPublicationSource;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanPageArtifact;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;
import com.fons.cloud.ai.trip.domain.service.impl.ChatConversationDomainServiceImpl;
import com.fons.cloud.ai.trip.infrastructure.render.ItineraryPlanHtmlRenderer;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanPageStore;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import com.fons.cloud.file.api.OssStoreService;
import com.fons.cloud.file.common.FileException;
import com.fons.cloud.file.common.request.OssObjectRequest;
import com.fons.cloud.file.common.request.OssUploadRequest;
import com.fons.cloud.file.common.response.OssObjectResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItineraryPlanPageApplicationServiceTest {

    private final CandidateOwner owner = new CandidateOwner("USER-1", "CONVERSATION-1");
    private final StubEligibilityValidatorService eligibilityService = new StubEligibilityValidatorService();
    private final StubHtmlRenderer renderer = new StubHtmlRenderer();
    private final RecordingOssStore ossStoreService = new RecordingOssStore();
    private final StubConversationDomainService conversationDomainService = new StubConversationDomainService();
    private ItineraryPlanPageApplicationService service;

    @BeforeEach
    void setUp() {
        service = new ItineraryPlanPageApplicationService(eligibilityService, renderer,
                new ItineraryPlanPageStore(ossStoreService), conversationDomainService);
    }

    @Test
    void storesReviewedPageAsUtf8HtmlUnderOwnerScopedKey() {
        eligibilityService.source = source();
        renderer.html = "<!doctype html><html>杭州行程</html>";

        ItineraryPlanPageArtifact artifact = service.create(owner, "PLAN-1");

        assertEquals(renderer.html, ossStoreService.uploadedHtml);
        assertEquals("text/html; charset=UTF-8", ossStoreService.metadata.get("content-type"));
        assertEquals("REVIEW-1", ossStoreService.metadata.get("trip-review-id"));
        assertTrue(ossStoreService.objectKey.startsWith("trip/itinerary/plans/"));
        assertTrue(ossStoreService.objectKey.endsWith("/PLAN-1.html"));
        assertFalse(ossStoreService.objectKey.contains(owner.userId()));
        assertEquals("PLAN-1", artifact.planId());
        assertEquals("REVIEW-1", artifact.reviewId());
        assertEquals("PROPOSAL-1", artifact.recommendedProposalId());
        assertEquals(ossStoreService.objectKey, artifact.objectKey());
    }

    @Test
    void newReviewOverwritesTheSamePlanPagePath() {
        eligibilityService.source = source();
        renderer.html = "<html>第一次审核</html>";
        ItineraryPlanPageArtifact first = service.create(owner, "PLAN-1");

        eligibilityService.source.reviewResult().setReviewId("REVIEW-2");
        renderer.html = "<html>重新审核</html>";
        ItineraryPlanPageArtifact second = service.create(owner, "PLAN-1");

        assertEquals(first.objectKey(), second.objectKey());
        assertEquals("REVIEW-2", second.reviewId());
        assertEquals("<html>重新审核</html>", ossStoreService.uploadedHtml);
        assertEquals(2, ossStoreService.uploadCount);
    }

    @Test
    void rejectedPlanNeverRendersOrUploads() {
        eligibilityService.reject = true;

        assertThrows(BusinessRuntimeException.class, () -> service.create(owner, "PLAN-1"));

        assertEquals(0, renderer.renderCount);
        assertEquals(0, ossStoreService.uploadCount);
    }

    @Test
    void failedUploadDoesNotReturnPageArtifact() {
        eligibilityService.source = source();
        renderer.html = "<html>行程</html>";
        ossStoreService.failUpload = true;

        assertThrows(SystemIntervalException.class, () -> service.create(owner, "PLAN-1"));
    }

    @Test
    void readsOnlyPageForCurrentReview() {
        eligibilityService.source = source();
        renderer.html = "<html>当前审核方案</html>";
        service.create(owner, "PLAN-1");

        assertEquals(renderer.html, service.read(owner, "PLAN-1"));

        eligibilityService.source.reviewResult().setReviewId("REVIEW-2");
        assertEquals(null, service.read(owner, "PLAN-1"));
    }

    @Test
    void pageIsUnavailableBeforeCreation() {
        eligibilityService.source = source();

        assertEquals(null, service.read(owner, "PLAN-1"));
    }

    @Test
    void conversationOwnershipIsCheckedBeforeReadingPlan() {
        eligibilityService.source = source();
        conversationDomainService.allowed = false;

        assertThrows(BusinessRuntimeException.class, () -> service.read(owner, "PLAN-1"));
    }

    private ItineraryPublicationSource source() {
        ItineraryPlanningResult plan = ItineraryPlanningResult.builder().planId("PLAN-1").build();
        ItineraryReviewResult review = ItineraryReviewResult.builder().reviewId("REVIEW-1").build();
        Proposal recommended = new Proposal("PROPOSAL-1", null, null, null, null,
                null, null, null, null, null, null);
        return new ItineraryPublicationSource(plan, review, recommended);
    }

    private static class StubEligibilityValidatorService extends ItineraryPublicationEligibilityValidatorService {

        private ItineraryPublicationSource source;
        private boolean reject;

        private StubEligibilityValidatorService() {
            super(null, null, null);
        }

        @Override
        public ItineraryPublicationSource requirePublishable(CandidateOwner owner, String planId) {
            if (reject) {
                throw BusinessRuntimeException.of(ResultCode.ILLEGAL_REQUEST_LIMITED.getCode(), "方案尚不可发布");
            }
            return source;
        }
    }

    private static class StubHtmlRenderer extends ItineraryPlanHtmlRenderer {

        private String html;
        private int renderCount;

        private StubHtmlRenderer() {
            super(null, null);
        }

        @Override
        public String render(ItineraryPublicationSource source) {
            renderCount++;
            return html;
        }
    }

    private static class StubConversationDomainService extends ChatConversationDomainServiceImpl {

        private boolean allowed = true;

        @Override
        public ChatConversation findByUseIdAndConversationId(String userId, String conversationId) {
            return allowed ? ChatConversation.builder().userId(userId).conversationId(conversationId).build() : null;
        }
    }

    private static class RecordingOssStore implements OssStoreService {

        private String objectKey;
        private String uploadedHtml;
        private Map<String, String> metadata;
        private int uploadCount;
        private boolean failUpload;

        @Override
        public OssObjectResponse upload(OssUploadRequest request) {
            uploadCount++;
            if (failUpload) {
                throw new FileException("OSS_OPERATION_FAILED", "上传失败");
            }
            objectKey = request.getObjectKey();
            metadata = request.getMetadata();
            try {
                uploadedHtml = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new AssertionError("测试无法读取上传内容", e);
            }
            return OssObjectResponse.builder().objectKey(objectKey).build();
        }

        @Override
        public OssObjectResponse download(OssObjectRequest request) {
            return OssObjectResponse.builder().objectKey(objectKey)
                    .inputStream(new ByteArrayInputStream(uploadedHtml.getBytes(StandardCharsets.UTF_8))).build();
        }

        @Override
        public boolean exists(OssObjectRequest request) {
            return objectKey != null && objectKey.equals(request.getObjectKey());
        }

        @Override
        public void delete(OssObjectRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public OssObjectResponse getObjectInfo(OssObjectRequest request) {
            return OssObjectResponse.builder().objectKey(objectKey).metadata(metadata).build();
        }

        @Override
        public String getAccessUrl(OssObjectRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
