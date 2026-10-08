package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;

import java.util.List;

/**
 * @author hongqy
 */
public interface ChatMessageDomainService extends IService<ChatMessage> {

    /**
     * 查询本轮请求之前的最近 N 条消息，返回顺序仍为时间正序。
     */
    List<ChatMessage> findRecentMessages(String conversationId, String excludedRunId, int limit);

    /**
     * 查询指定会话中的审批消息，用于恢复前的归属校验。
     */
    ChatMessage findHitlMessage(String conversationId, String messageId);
}
