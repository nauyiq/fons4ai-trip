package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;

import java.time.LocalDate;

/**
 * 当前修复任务的行程边界。城市或日期变化后视为新的规划任务。
 *
 * @author hongqy
 */
public record ItineraryScope(String origin, String destination,
                             LocalDate departureDate, LocalDate returnDate) {

    public static ItineraryScope from(ItineraryPlanRequest request) {
        return new ItineraryScope(request.getOrigin(), request.getDestination(),
                request.getDepartureDate(), request.getReturnDate());
    }

    public static ItineraryScope from(ItineraryPlanningResult result) {
        ItineraryPlanningResult.TripRequest request = result.getUserRequest();
        return new ItineraryScope(request.origin(), request.destination(),
                request.departureDate(), request.returnDate());
    }
}
