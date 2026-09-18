package com.fons.cloud.ai.trip.infrastructure.client.model;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.Builder;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;

/**
 * 途牛火车票首页搜索参数；翻页使用TuniuSearchPageRequest。
 *
 * @author hongqy
 */
@Builder
public record TuniuTrainSearchRequest(
        /** 出发城市名称。 */
        String departureCityName,
        /** 到达城市名称。 */
        String arrivalCityName,
        /** 出发日期。 */
        @JSONField(format = "yyyy-MM-dd") LocalDate departureDate,
        /** 可选排序方式，不传时供应商默认价格升序。 */
        SearchType searchType,
        /** 可选出发时间范围，格式HH:mm-HH:mm。 */
        String departureTime,
        /** 可选到达时间范围，格式HH:mm-HH:mm。 */
        String arrivalTime) {

    /** 供应商排序代码，避免调用方直接填写数字字符串。 */
    @RequiredArgsConstructor
    public enum SearchType {
        /** 出发时间升序。 */
        DEPARTURE_ASC("1"),
        /** 出发时间降序。 */
        DEPARTURE_DESC("2"),
        /** 行程时长升序。 */
        DURATION_ASC("3"),
        /** 行程时长降序。 */
        DURATION_DESC("4"),
        /** 价格升序，供应商默认排序。 */
        PRICE_ASC("5"),
        /** 价格降序。 */
        PRICE_DESC("6");

        private final String code;

        @JSONField(value = true)
        public String getCode() {
            return code;
        }
    }
}
