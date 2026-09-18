package com.fons.cloud.ai.trip.infrastructure.client.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Builder;

import java.time.LocalDate;

/**
 * 途牛国内机票搜索参数，不包含用户凭据。翻页时保留原搜索条件并指定pageNum。
 *
 * @author hongqy
 */
@Builder
public record TuniuFlightSearchRequest(
        /** 出发城市名称。 */
        String departureCityName,
        /** 到达城市名称。 */
        String arrivalCityName,
        /** 出发日期。 */
        @JSONField(format = "yyyy-MM-dd") LocalDate departureDate,
        /** 可选查询模式；不传时采用供应商默认低价排序。 */
        SearchType searchType,
        /** TIME模式的出发时间范围，格式HH:mm-HH:mm。 */
        String departureTime,
        /** TIME模式的到达时间范围，格式HH:mm-HH:mm。 */
        String arrivalTime,
        /** PRICE模式的价格范围，格式最低价-最高价，单位元。 */
        String priceRange,
        /** 首页不传；翻页从2开始。 */
        Integer pageNum) {

    /** 供应商机票搜索模式，枚举名称即协议值。 */
    public enum SearchType {
        /** 按时间范围搜索。 */
        TIME,
        /** 按价格范围搜索。 */
        PRICE,
        /** 搜索附近出发机场。 */
        NEAR_GO,
        /** 搜索附近到达机场。 */
        NEAR_BACK,
        /** 搜索中转航班。 */
        TRANSFER
    }
}
