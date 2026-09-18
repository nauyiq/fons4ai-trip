package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.constants.SearchPriceBasis;

import java.math.BigDecimal;

/**
 * 标准候选报价，不将空价格、未提供税费或未知计价口径转换成默认值。
 * 是否可参与规划，由应用层根据完整性和报价口径判断；搜索报价不保证可预订或不会变化。
 *
 * @param amount 报价金额，单位为currency的主货币单位，未知时为null；不得用0代表缺失
 * @param currency ISO 4217币种编码，第一阶段为CNY；供应商其他币种不能直接当成CNY
 * @param basis 计价口径，必填，未明确时为UNKNOWN
 * @param taxIncluded amount是否包含已知税费，无法确认时为null
 * @param taxAmount 已知税费金额，未知时为null；taxIncluded=true时不可再重复加税
 * @param startingPrice 是否仅为起价，无法确认时为null；true不能直接当成具体可预订报价
 * @author hongqy
 */
public record CandidatePrice(BigDecimal amount, String currency, SearchPriceBasis basis,
                              Boolean taxIncluded, BigDecimal taxAmount, Boolean startingPrice) {
}
