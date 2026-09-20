package com.fons.cloud.ai.trip.common.dto;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.ResultCode;

import java.time.LocalDate;
import java.util.List;

/**
 * 标准候选分组，不包含价格筛选、关键词、排序、供应商续查凭据或Redis Key。
 * 交通按路线和日期分组，酒店额外区分入住日期、成人数和儿童年龄。
 * 城市只去除首尾空格，不擅自将不同城市名称认定为同一个城市。
 *
 * @param owner       可信数据归属
 * @param type        FLIGHT、TRAIN或HOTEL
 * @param origin      交通出发城市或酒店城市
 * @param destination 交通到达城市，酒店为null
 * @param startDate   交通出发日期或酒店入住日期
 * @param endDate     酒店离店日期，交通为null
 * @param adultCount  酒店成人数，交通为null
 * @param childAges   酒店儿童年龄，排序后保存；交通为空列表
 * @author hongqy
 */
public record ItineraryCandidateScope(CandidateOwner owner, BookingType type, String origin,
                                      String destination, LocalDate startDate, LocalDate endDate,
                                      Integer adultCount, List<Integer> childAges) {

    public ItineraryCandidateScope {
        Assert.isTrue(owner != null && type != null && origin != null && !origin.isBlank() && startDate != null,
                () -> parameterError("候选分组的归属、类型、城市和日期不能为空"));
        origin = origin.trim();
        if (type == BookingType.HOTEL) {
            Assert.isTrue(destination == null && endDate != null && endDate.isAfter(startDate)
                            && adultCount != null && adultCount > 0,
                    () -> parameterError("酒店候选分组需要正确的入住、离店日期和成人数"));
            List<Integer> ages = childAges == null ? List.of() : childAges;
            Assert.isTrue(ages.stream().allMatch(age -> age != null && age >= 0),
                    () -> parameterError("儿童年龄不能为空或负数"));
            childAges = ages.stream().sorted().toList();
        } else {
            Assert.isTrue((type == BookingType.FLIGHT || type == BookingType.TRAIN)
                            && destination != null && !destination.isBlank() && !origin.equalsIgnoreCase(destination.trim())
                            && endDate == null && adultCount == null && (childAges == null || childAges.isEmpty()),
                    () -> parameterError("交通候选分组需要不同的出发、到达城市，不接受住宿条件"));
            destination = destination.trim();
            childAges = List.of();
        }
    }

    public static ItineraryCandidateScope transport(CandidateOwner owner, BookingType type,
                                                    String origin, String destination, LocalDate date) {
        return new ItineraryCandidateScope(owner, type, origin, destination, date, null, null, List.of());
    }

    public static ItineraryCandidateScope hotel(CandidateOwner owner, String city, LocalDate checkIn,
                                                LocalDate checkOut, int adultCount, List<Integer> childAges) {
        return new ItineraryCandidateScope(owner, BookingType.HOTEL, city, null, checkIn, checkOut, adultCount, childAges);
    }

    private static BusinessRuntimeException parameterError(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }
}
