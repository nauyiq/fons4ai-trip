package com.fons.cloud.ai.trip.infrastructure.client.model;

import java.util.List;

/**
 * 途牛国内机票搜索响应，保留供应商字段，不承担Trip候选转换或评分。
 *
 * @author hongqy
 */
public record TuniuFlightSearchResponse(
        Boolean successCode,
        /** 供应商查询标识，供后续关联查询使用。 */
        String queryId,
        /** 总页数。 */
        Integer totalPageNum,
        /** 航班列表；空列表表示本次搜索无匹配结果。 */
        List<Flight> data) {

    /** 时间、金额及余票保留供应商字符串格式，标准化由客户端内部转换器处理。 */
    public record Flight(String flightNumber, String airlineCompany,
                         String departureTime, String arrivalTime,
                         String departureAirport, String arrivalAirport,
                         String departureTerminal, String arrivalTerminal,
                         String basePrice, String totalTax, String cabinClass,
                         String remainingSeats, String totalDuration, String flyTime,
                         String type, String craftType, String shareFlightNo) {
    }
}
