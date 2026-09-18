package com.fons.cloud.ai.trip.infrastructure.client.model;

import lombok.Builder;

/**
 * 途牛火车、酒店搜索翻页参数。queryId必须来自同一用户原搜索结果，pageNum从2开始。
 *
 * @author hongqy
 */
@Builder
public record TuniuSearchPageRequest(
        /** 当前用户原搜索响应中的供应商查询标识。 */
        String queryId,
        /** 后续页页码，从2开始。 */
        Integer pageNum) {
}
