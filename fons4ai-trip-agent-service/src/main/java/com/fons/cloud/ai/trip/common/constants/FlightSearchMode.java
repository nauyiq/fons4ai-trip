package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Trip机票搜索方式，具体供应商参数由客户端转换；不支持的方式应明确报错。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum FlightSearchMode {

    LOWEST_PRICE("优先低价"),
    TIME_RANGE("指定时间范围"),
    PRICE_RANGE("指定价格范围"),
    NEAR_DEPARTURE("附近出发机场"),
    NEAR_ARRIVAL("附近到达机场"),
    TRANSFER("中转航班");

    /** 搜索方式说明，不直接作为供应商协议值。 */
    private final String label;
}
