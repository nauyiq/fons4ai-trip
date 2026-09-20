package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.SearchPriceBasis;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateSnapshot.SelectedCandidates;
import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 通过业务可计算性过滤后，实际参与组合计算的候选集合。
 * 原始候选仍保留在候选快照中，本对象不修改Redis数据。
 *
 * @param outbound 可计算的去程交通候选
 * @param inbound 可计算的返程交通候选
 * @param hotels 可计算的酒店候选
 * @param rejectedCandidates 被过滤的候选及原因
 * @author hongqy
 */
public record ItineraryPlanningCandidates(List<TransportCandidate> outbound,
                                          List<TransportCandidate> inbound,
                                          List<HotelCandidate> hotels,
                                          List<RejectedCandidate> rejectedCandidates) {

    private static final String PLANNING_CURRENCY = "CNY";

    public ItineraryPlanningCandidates {
        outbound = List.copyOf(outbound);
        inbound = List.copyOf(inbound);
        hotels = List.copyOf(hotels);
        rejectedCandidates = List.copyOf(rejectedCandidates);
    }

    /**
     * 将已应用排除项的候选转换为可计算候选，任何缺失值都不会按0或默认值参与计算。
     *
     * @param request 已归一化并通过基础校验的规划请求
     * @param selectedCandidates 已应用明确排除项的候选集合
     * @return 转换结果；至少一类候选全部失效时通过errors说明原因
     */
    public static ConversionResult prepare(ItineraryPlanRequest request,
                                           SelectedCandidates selectedCandidates) {
        List<RejectedCandidate> rejectedCandidates = new ArrayList<>();
        List<TransportCandidate> outbound = filterTransports(selectedCandidates.outbound(),
                request.getOrigin(), request.getDestination(), request.getDepartureDate(), rejectedCandidates);
        List<TransportCandidate> inbound = filterTransports(selectedCandidates.inbound(),
                request.getDestination(), request.getOrigin(), request.getReturnDate(), rejectedCandidates);
        List<HotelCandidate> hotels = filterHotels(selectedCandidates.hotels(), request, rejectedCandidates);

        List<String> errors = new ArrayList<>();
        if (outbound.isEmpty()) {
            errors.add("去程候选均缺少可用于规划的完整路线、时间、库存或确定价格");
        }
        if (inbound.isEmpty()) {
            errors.add("返程候选均缺少可用于规划的完整路线、时间、库存或确定价格");
        }
        if (hotels.isEmpty()) {
            errors.add("住宿候选均缺少可用于规划的确定单间每晚价格，请补充具体房型报价后重试");
        }
        return new ConversionResult(
                new ItineraryPlanningCandidates(outbound, inbound, hotels, rejectedCandidates), errors);
    }

    private static List<TransportCandidate> filterTransports(List<TransportCandidate> source,
                                                              String expectedOrigin,
                                                              String expectedDestination,
                                                              LocalDate expectedDate,
                                                              List<RejectedCandidate> rejectedCandidates) {
        List<TransportCandidate> result = new ArrayList<>();
        for (TransportCandidate candidate : source) {
            String reason = invalidTransportReason(candidate, expectedOrigin, expectedDestination, expectedDate);
            if (reason == null) {
                result.add(candidate);
            } else {
                rejectedCandidates.add(new RejectedCandidate(candidate.candidateId(), reason));
            }
        }
        return result;
    }

    private static String invalidTransportReason(TransportCandidate candidate,
                                                 String expectedOrigin,
                                                 String expectedDestination,
                                                 LocalDate expectedDate) {
        if (StringUtils.isBlank(candidate.origin()) || StringUtils.isBlank(candidate.destination())) {
            return "缺少实际出发城市或到达城市";
        }
        if (!expectedOrigin.equalsIgnoreCase(candidate.origin().trim())
                || !expectedDestination.equalsIgnoreCase(candidate.destination().trim())) {
            return "实际路线与本次规划路线不一致";
        }
        if (candidate.departureTime() == null || candidate.arrivalTime() == null) {
            return "缺少完整出发或到达时间";
        }
        if (!expectedDate.equals(candidate.departureTime().toLocalDate())) {
            return "实际出发日期与本次规划日期不一致";
        }
        if (!candidate.arrivalTime().isAfter(candidate.departureTime())) {
            return "到达时间不晚于出发时间";
        }
        long actualMinutes = Duration.between(candidate.departureTime(), candidate.arrivalTime()).toMinutes();
        if (candidate.transitMinutes() == null || candidate.transitMinutes() <= 0
                || candidate.transitMinutes() != actualMinutes) {
            return "交通耗时缺失或与出发到达时间不一致";
        }
        if (candidate.remainingSeats() != null && candidate.remainingSeats() == 0) {
            return "当前报价已无余票";
        }
        String priceError = invalidPriceReason(candidate.price(), SearchPriceBasis.PER_PERSON_ONE_WAY);
        if (priceError != null) {
            return priceError;
        }
        if (candidate.type() == BookingType.FLIGHT && !Boolean.TRUE.equals(candidate.price().taxIncluded())) {
            return "机票报价未确认包含税费";
        }
        return null;
    }

    private static List<HotelCandidate> filterHotels(List<HotelCandidate> source,
                                                     ItineraryPlanRequest request,
                                                     List<RejectedCandidate> rejectedCandidates) {
        List<HotelCandidate> result = new ArrayList<>();
        int expectedNights = Math.toIntExact(ChronoUnit.DAYS.between(
                request.getDepartureDate(), request.getReturnDate()));
        for (HotelCandidate candidate : source) {
            String reason = invalidHotelReason(candidate, request, expectedNights);
            if (reason == null) {
                result.add(candidate);
            } else {
                rejectedCandidates.add(new RejectedCandidate(candidate.candidateId(), reason));
            }
        }
        return result;
    }

    private static String invalidHotelReason(HotelCandidate candidate,
                                             ItineraryPlanRequest request,
                                             int expectedNights) {
        if (StringUtils.isBlank(candidate.city())
                || !request.getDestination().equalsIgnoreCase(candidate.city().trim())) {
            return "酒店城市与本次规划目的地不一致";
        }
        if (!request.getDepartureDate().equals(candidate.checkInDate())
                || !request.getReturnDate().equals(candidate.checkOutDate())
                || candidate.nights() != expectedNights) {
            return "酒店入住日期或晚数与本次规划不一致";
        }
        if (candidate.source() == null || StringUtils.isAnyBlank(candidate.source().itemId(),
                candidate.source().offerId(), candidate.roomTypeId(), candidate.roomType())) {
            return "缺少具体酒店、房型或报价方案标识";
        }
        if (candidate.remainingRooms() != null && candidate.remainingRooms() <= 0) {
            return "当前房型报价已无库存";
        }
        int guestCount = request.getAdultCount() + request.getChildAges().size();
        if (candidate.maxOccupancy() != null && candidate.maxOccupancy() > 0
                && guestCount > candidate.maxOccupancy()) {
            return "房型最大入住人数不足";
        }
        return invalidPriceReason(candidate.price(), SearchPriceBasis.PER_ROOM_PER_NIGHT);
    }

    private static String invalidPriceReason(CandidatePrice price, SearchPriceBasis expectedBasis) {
        if (price == null || price.amount() == null || price.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return "报价金额缺失或无效";
        }
        if (!PLANNING_CURRENCY.equalsIgnoreCase(StringUtils.trimToEmpty(price.currency()))) {
            return "报价币种不是当前规划支持的CNY";
        }
        if (price.basis() != expectedBasis) {
            return "报价计价口径不满足规划要求";
        }
        if (!Boolean.FALSE.equals(price.startingPrice())) {
            return "报价仅为起价或未确认是否为确定价格";
        }
        return null;
    }

    /** 被过滤的单个候选及其确定原因。 */
    public record RejectedCandidate(String candidateId, String reason) {
    }

    /** 可计算候选转换结果。 */
    public record ConversionResult(ItineraryPlanningCandidates candidates, List<String> errors) {

        public ConversionResult {
            errors = List.copyOf(errors);
        }
    }
}
