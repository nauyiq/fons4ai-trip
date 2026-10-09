package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;

import java.util.List;

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
     * 将 Pipeline 运行 ID 写入 conversation.activeRunId
     * @param conversationId
     * @param pipelineRunId
     * @return
     */
    boolean updateActiveRunId(String conversationId, String pipelineRunId);

    /**
     * 只清理仍属于指定 Pipeline 运行的活跃 ID，避免旧运行收尾覆盖新运行。
     */
    boolean clearActiveRunIdIfMatch(String conversationId, String pipelineRunId);

    /**
     * 根据用户ID查询会话列表
     * @param userId
     * @return
     */
    List<ChatConversation> listByUserId(String userId);
}
