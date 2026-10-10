package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TravelOrderReference;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewEvidence;
import com.fons.cloud.common.base.exception.SystemIntervalException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单次行程审核共享的可信运行上下文。
 * 固定审核时间和业务日期，避免同一轮中不同审核器读取到不同时间；当前差旅单由应用层重新查询后提供。
 *
 * @param reviewedAt 本次审核时间
 * @param businessDate 当前业务日期，使用应用JVM默认时区
 * @param currentTravelOrder 当前差旅单可信快照；独立规划或尚未加载时为null
 * @param facts 按本次审核事实标识索引的可引用证据
 * @param coverageGaps 未获取的可选外部信息，不据此推断无风险
 * @author hongqy
 */
public record ItineraryReviewContext(OffsetDateTime reviewedAt,
                                     LocalDate businessDate,
                                     TravelOrderReference currentTravelOrder,
                                     Map<String, ItineraryReviewEvidence> facts,
                                     List<ItineraryReviewCoverageGap> coverageGaps) {

    public ItineraryReviewContext {
        if (reviewedAt == null) {
            throw SystemIntervalException.of("审核时间不能为空");
        }
        if (businessDate == null) {
            throw SystemIntervalException.of("审核业务日期不能为空");
        }
        facts = facts == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(facts));
        if (coverageGaps == null) {
            coverageGaps = List.of();
        } else {
            for (ItineraryReviewCoverageGap gap : coverageGaps) {
                if (gap == null) {
                    throw SystemIntervalException.of("审核信息覆盖缺口不能包含null");
                }
            }
            coverageGaps = List.copyOf(coverageGaps);
        }
    }

    public static ItineraryReviewContext now(TravelOrderReference currentTravelOrder) {
        Instant now = Instant.now();
        return new ItineraryReviewContext(now.atOffset(ZoneOffset.UTC),
                now.atZone(ZoneId.systemDefault()).toLocalDate(), currentTravelOrder,
                Map.of(), List.of());
    }
}
