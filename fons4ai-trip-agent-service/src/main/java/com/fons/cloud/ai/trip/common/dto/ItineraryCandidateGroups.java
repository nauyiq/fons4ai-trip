package com.fons.cloud.ai.trip.common.dto;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.ResultCode;

import java.time.LocalDate;
import java.util.List;

/**
 * 应用层为一次规划构造的五个候选分组，全部属于相同用户和差旅单。
 * 不含Redis Key；存储层据此批量读取，行程日期和入住人数由应用层决定。
 *
 * @param outboundFlights 去程机票分组
 * @param outboundTrains  去程火车分组
 * @param inboundFlights  返程机票分组
 * @param inboundTrains   返程火车分组
 * @param hotels          酒店分组
 * @author hongqy
 */
public record ItineraryCandidateGroups(ItineraryCandidateScope outboundFlights,
                                       ItineraryCandidateScope outboundTrains,
                                       ItineraryCandidateScope inboundFlights,
                                       ItineraryCandidateScope inboundTrains,
                                       ItineraryCandidateScope hotels) {

    public ItineraryCandidateGroups {
        Assert.isTrue(outboundFlights != null && outboundTrains != null && inboundFlights != null && inboundTrains != null && hotels != null, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "规划候选分组不能为空"));
        Assert.isTrue(outboundFlights.type() == BookingType.FLIGHT && outboundTrains.type() == BookingType.TRAIN
                        && inboundFlights.type() == BookingType.FLIGHT && inboundTrains.type() == BookingType.TRAIN
                        && hotels.type() == BookingType.HOTEL,
                () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "规划候选分组类型不正确"));
        Assert.isTrue(List.of(outboundTrains, inboundFlights, inboundTrains, hotels).stream()
                        .allMatch(scope -> outboundFlights.userId().equals(scope.userId())
                                && outboundFlights.orderId().equals(scope.orderId())),
                () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "规划候选分组必须属于相同用户和差旅单"));
    }

    /**
     * 按单目的城市往返行程构造固定的五个候选分组。
     *
     * @param userId 可信用户标识
     * @param orderId 差旅单标识
     * @param origin 出发城市
     * @param destination 目的城市
     * @param departureDate 去程日期，同时作为酒店入住日期
     * @param returnDate 返程日期，同时作为酒店离店日期
     * @param adultCount 酒店成人数
     * @param childAges 酒店儿童年龄
     * @return 去返程机票、火车票及酒店候选分组
     */
    public static ItineraryCandidateGroups roundTrip(String userId, String orderId,
                                                      String origin,
                                                      String destination,
                                                      LocalDate departureDate,
                                                      LocalDate returnDate,
                                                      int adultCount,
                                                      List<Integer> childAges) {
        return new ItineraryCandidateGroups(
                ItineraryCandidateScope.transport(userId, orderId, BookingType.FLIGHT,
                        origin, destination, departureDate),
                ItineraryCandidateScope.transport(userId, orderId, BookingType.TRAIN,
                        origin, destination, departureDate),
                ItineraryCandidateScope.transport(userId, orderId, BookingType.FLIGHT,
                        destination, origin, returnDate),
                ItineraryCandidateScope.transport(userId, orderId, BookingType.TRAIN,
                        destination, origin, returnDate),
                ItineraryCandidateScope.hotel(userId, orderId, destination, departureDate,
                        returnDate, adultCount, childAges));
    }
}
