package com.fons.cloud.ai.trip.common.dto;

/**
 * 政策校验使用的单笔订单摘要。
 *
 * @param type        FLIGHT、HOTEL、TRAIN，忽略大小写及首尾空格
 * @param amount      酒店每晚房费（元），不是多晚订单总额；其他类型不使用此字段
 * @param flightClass 机票舱位，仅 FLIGHT 使用
 * @param seatClass   火车席别，仅 TRAIN 使用
 * @author hongqy
 */
public record TravelPolicyOrderSummary(
        String type,
        Double amount,
        String flightClass,
        String seatClass) {
}
