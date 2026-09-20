package com.fons.cloud.ai.trip.common.dto;

import java.math.BigDecimal;

/**
 * 搜索价格范围，第一阶段单位为人民币元，不附加货币符号。
 * 两端均必填且非负，maximum不能小于minimum；由客户端校验。
 * 搜索过滤口径以供应商能力为准，不代表返回报价已经包含税费。
 *
 * @param minimum 最低价
 * @param maximum 最高价
 * @author hongqy
 */
public record SearchPriceRange(BigDecimal minimum, BigDecimal maximum) {
}
