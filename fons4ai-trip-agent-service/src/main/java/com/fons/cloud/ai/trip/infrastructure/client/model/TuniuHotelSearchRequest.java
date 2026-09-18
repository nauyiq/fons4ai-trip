package com.fons.cloud.ai.trip.infrastructure.client.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Builder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 途牛酒店首页搜索参数。Trip要求明确提供入住、离店日期，避免供应商默认查询今天。
 * 翻页使用TuniuSearchPageRequest，不重复携带首页条件。
 *
 * @author hongqy
 */
@Builder
public record TuniuHotelSearchRequest(
        /** 城市名称。 */
        String cityName,
        /** 入住日期。 */
        @JSONField(format = "yyyy-MM-dd") LocalDate checkIn,
        /** 离店日期，必须晚于入住日期。 */
        @JSONField(format = "yyyy-MM-dd") LocalDate checkOut,
        /** 可选关键词，多个关键词用空格分隔。 */
        String keyword,
        /** 可选目标地点名称，供应商按周边范围搜索。 */
        String poiName,
        /** 可选价格区间，格式最低价-最高价，单位元。 */
        String prices,
        /** 可选成人数，必须大于0；不传采用供应商默认值2。 */
        Integer adultNum,
        /** 可选儿童数，不传表示0。 */
        Integer childNum,
        /** 儿童年龄列表，数量须与儿童数一致。 */
        List<Integer> childAges) {

    public TuniuHotelSearchRequest {
        if (childAges != null) {
            // 快照调用方列表，避免请求发送过程中被修改；具体年龄校验由客户端执行。
            childAges = Collections.unmodifiableList(new ArrayList<>(childAges));
        }
    }
}
