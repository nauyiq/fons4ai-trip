package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Trip火车搜索排序方式，供应商数字代码在客户端内部转换。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum TrainSearchSort {

    DEPARTURE_ASC("出发时间升序"),
    DEPARTURE_DESC("出发时间降序"),
    DURATION_ASC("耗时升序"),
    DURATION_DESC("耗时降序"),
    PRICE_ASC("价格升序"),
    PRICE_DESC("价格降序");

    /** 排序方式说明。 */
    private final String label;
}
