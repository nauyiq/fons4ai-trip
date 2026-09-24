package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;

/**
 * @author hongqy
 */
public interface ChatConversationDomainService extends IService<ChatConversation> {

    /**
     * 根据用户ID和会话ID查询会话
     * @param useId
     * @param conversationId
     * @return
     */
    ChatConversation findByUseIdAndConversationId(String useId, String conversationId);

    /**
     * 更新conversation中的activeRunId
     * @param conversationId
     * @param runId
     * @return
     */
    boolean updateActiveRunId(String conversationId, String runId);
}
