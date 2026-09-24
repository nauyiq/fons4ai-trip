package com.fons.cloud.ai.trip.common.request;

/**
 * 会话中断请求
 * @param userId         用户ID
 * @param conversationId 会话ID
 * @author hongqy
 */
public record ConversationInterruptRequest(String userId, String conversationId) {


}
