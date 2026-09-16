package com.fons.cloud.ai.trip.common.dto;

/**
 * 关联预订摘要（用于在取消/修改结果中告知 LLM 受影响的预订）
 * @author hongqy
 */
public record BookingSummary(String bookingId, String bizType, String title, String status, String externalOrderNo) {
}
