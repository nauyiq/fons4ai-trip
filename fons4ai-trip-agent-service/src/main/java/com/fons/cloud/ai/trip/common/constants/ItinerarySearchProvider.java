package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 行程搜索供应商标识，用于选择客户端策略及追溯候选来源，不承担个人凭据管理。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItinerarySearchProvider {

    TU_NIU("TU_NIU", "途牛");

    /**
     * 稳定的供应商编码，不使用MCP工具名或CLI名称作为业务标识。
     */
    private final String code;

    /**
     * 供应商显示名称。
     */
    private final String label;
}
