package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TravelOrderReference;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * 单次行程审核共享的可信运行上下文。
 * 固定审核时间和业务日期，避免同一轮中不同审核器读取到不同时间；当前差旅单由应用层重新查询后提供。
 *
 * @param reviewedAt 本次审核时间
 * @param businessDate 当前业务日期，使用应用JVM默认时区
 * @param currentTravelOrder 当前差旅单可信快照；独立规划或尚未加载时为null
 * @author hongqy
 */
public record ItineraryReviewContext(OffsetDateTime reviewedAt,
                                     LocalDate businessDate,
                                     TravelOrderReference currentTravelOrder) {

    public ItineraryReviewContext {
        Objects.requireNonNull(reviewedAt, "审核时间不能为空");
        Objects.requireNonNull(businessDate, "审核业务日期不能为空");
    }

    public static ItineraryReviewContext now(TravelOrderReference currentTravelOrder) {
        Instant now = Instant.now();
        return new ItineraryReviewContext(now.atOffset(ZoneOffset.UTC),
                now.atZone(ZoneId.systemDefault()).toLocalDate(), currentTravelOrder);
    }
}
