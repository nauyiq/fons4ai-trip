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
     * 获取人工审批消息
     * @param conversationId
     * @param runId
     * @param hitlId
     * @return
     */
    ChatMessage findHitlMessages(String conversationId, String runId, String hitlId);
}
