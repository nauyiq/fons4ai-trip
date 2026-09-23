package com.fons.cloud.ai.trip.common.request;

import lombok.Builder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Trip酒店具体房型报价搜索契约。酒店标识来自酒店列表候选的source.itemId，
 * 第一阶段固定查询一间房，不携带供应商工具名称、API Key或预订临时参数。
 *
 * @param hotelItemId 供应商酒店商品标识，必填
 * @param city 酒店所在城市，必填，用于候选分组及结果一致性校验
 * @param checkInDate 入住日期，必填
 * @param checkOutDate 离店日期，必填且晚于入住日期
 * @param adultCount 成人数，null表示2，必须大于0
 * @param childAges 儿童年龄列表，null或空列表表示没有儿童
 * @author hongqy
 */
@Builder
public record HotelRoomSearchRequest(String hotelItemId, String city,
                                     LocalDate checkInDate, LocalDate checkOutDate,
                                     Integer adultCount, List<Integer> childAges) {

    public HotelRoomSearchRequest {
        if (childAges != null) {
            childAges = Collections.unmodifiableList(new ArrayList<>(childAges));
        }
    }
}
