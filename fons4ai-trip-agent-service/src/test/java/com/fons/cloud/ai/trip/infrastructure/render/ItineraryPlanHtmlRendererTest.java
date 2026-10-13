package com.fons.cloud.ai.trip.infrastructure.render;

import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.dto.ItineraryPublicationSource;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.HotelOption;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Metrics;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.ProposalTag;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Scores;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TransportOption;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TripRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryProposalReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewIssue;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItineraryPlanHtmlRendererTest {

    private ItineraryPlanHtmlRenderer renderer;

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        renderer = new ItineraryPlanHtmlRenderer(engine, new ItineraryPlanPageViewAssembler());
    }

    @Test
    void rendersReviewedRecommendationAndBlockedAlternativeWithoutInventingDetails() {
        String html = renderer.render(publicationSource());

        assertTrue(html.contains("上海 → 杭州"));
        assertTrue(html.contains("¥1680"));
        assertTrue(html.contains("方案 P-RECOMMENDED"));
        assertTrue(html.contains("方案 P-BLOCKED"));
        assertTrue(html.contains("当前不可推荐"));
        assertTrue(html.contains("2026-10-16 08:00 +08:00"));
        assertTrue(html.contains("未提供"));
        assertFalse(html.contains("最符合偏好"));
        assertFalse(html.contains("th:text="));
    }

    @Test
    void escapesSupplierAndReviewTextInsteadOfEmittingExecutableMarkup() {
        String html = renderer.render(publicationSource());

        assertTrue(html.contains("&lt;script&gt;alert(1)&lt;/script&gt;"));
        assertFalse(html.contains("<script>alert(1)</script>"));
        assertFalse(html.contains("<script"));
    }

    private ItineraryPublicationSource publicationSource() {
        OffsetDateTime departure = OffsetDateTime.parse("2026-10-16T08:00:00+08:00");
        TransportOption outbound = new TransportOption("OUT-1", BookingType.TRAIN, "中国铁路", "G100",
                "上海", "杭州", departure, departure.plusHours(1), 60,
                new BigDecimal("400"), "二等座", true, null);
        TransportOption inbound = new TransportOption("IN-1", BookingType.TRAIN, "中国铁路", "G101",
                "杭州", "上海", departure.plusDays(2), departure.plusDays(2).plusHours(1), 60,
                new BigDecimal("400"), "二等座", true, null);
        HotelOption hotel = new HotelOption("HOTEL-1", "酒店<script>alert(1)</script>", "杭州",
                null, null, "大床房", LocalDate.parse("2026-10-16"), LocalDate.parse("2026-10-18"),
                2, new BigDecimal("440"), null, null, null);
        Metrics metrics = new Metrics(new BigDecimal("880"), new BigDecimal("1680"), 120,
                new BigDecimal("48"));
        Scores scores = new Scores(new BigDecimal("80"), new BigDecimal("70"),
                new BigDecimal("50"), null, new BigDecimal("75"), new BigDecimal("75"),
                new BigDecimal("76"));
        Proposal recommended = new Proposal("P-RECOMMENDED",
                List.of(ProposalTag.OVERALL_BEST, ProposalTag.PREFERENCE_BEST), outbound, hotel, inbound,
                metrics, scores, null, List.of(), List.of("价格可能变化"), List.of());
        Proposal blocked = new Proposal("P-BLOCKED", List.of(ProposalTag.PRICE_PREFERRED),
                outbound, hotel, inbound, metrics, scores, null, List.of(), List.of(), List.of());
        ItineraryPlanningResult plan = ItineraryPlanningResult.builder().planId("PLAN-1")
                .generatedAt(departure.minusDays(1))
                .userRequest(new TripRequest("上海", "杭州", departure.toLocalDate(),
                        departure.plusDays(2).toLocalDate()))
                .currency("CNY").weatherSummary(null).preferences("")
                .proposals(List.of(recommended, blocked)).build();
        ItineraryReviewResult review = ItineraryReviewResult.builder().reviewId("REVIEW-1")
                .planId("PLAN-1").reviewedAt(departure.minusHours(1))
                .executionStatus(ItineraryReviewExecutionStatus.COMPLETE)
                .verdict(ItineraryReviewVerdict.WARNING)
                .nextAction(ItineraryReviewNextAction.PROCEED)
                .recommendedProposalId("P-RECOMMENDED")
                .summary("推荐方案有一项风险提醒")
                .proposalReviews(List.of(
                        new ItineraryProposalReview("P-RECOMMENDED", ItineraryReviewVerdict.WARNING,
                                true, List.of("ISSUE-1")),
                        new ItineraryProposalReview("P-BLOCKED", ItineraryReviewVerdict.BLOCKED,
                                false, List.of("ISSUE-2"))))
                .issues(List.of(
                        new ItineraryReviewIssue("ISSUE-1", null, null, null, false, false,
                                List.of("P-RECOMMENDED"), List.of(), "注意<script>alert(1)</script>"),
                        new ItineraryReviewIssue("ISSUE-2", null, null, null, true, false,
                                List.of("P-BLOCKED"), List.of(), "方案不可推荐")))
                .build();
        return new ItineraryPublicationSource(plan, review, recommended);
    }
}
