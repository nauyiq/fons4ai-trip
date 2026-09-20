package com.fons.cloud.ai.trip.common.dto;

import java.time.LocalTime;

/**
 * 搜索时间范围，按出发地或到达地的当地时间解释，第一阶段不跨午夜。
 * 两端均必填，end不能早于start；由客户端校验并转换成供应商需要的字符串。
 *
 * @param start 开始时间，精度为分钟
 * @param end 结束时间，精度为分钟
 * @author hongqy
 */
public record SearchTimeRange(LocalTime start, LocalTime end) {
}
