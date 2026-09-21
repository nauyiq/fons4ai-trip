package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;

import java.util.List;

/**
 * @author hongqy
 */
public interface ChatMessageDomainService extends IService<ChatMessage> {

    /**
     * 查询某会话下最近 N 条消息（按时间正序返回）。
     * @param conversationId
     * @param limit
     * @return
     */
    List<ChatMessage> findRecentMessages(String conversationId, int limit);

}
