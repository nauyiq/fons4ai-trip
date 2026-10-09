package com.fons.cloud.ai.trip.common.request;

/**
 * @author hongqy
 */
public record PageMessageRequest(String conversationId, String userId, int page, int pageSize) {
}
