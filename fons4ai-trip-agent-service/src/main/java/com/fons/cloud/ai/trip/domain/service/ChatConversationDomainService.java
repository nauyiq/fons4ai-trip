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

    /** 仅逻辑删除当前用户没有活跃Run的会话，条件与删除在同一条SQL中完成。 */
    boolean removeIdleConversation(String userId, String conversationId);

    /**
     * 仅在会话空闲时取得执行权，条件检查与写入必须在同一条SQL中完成。
     * 已有活跃Run时返回false，不能覆盖旧Run或自动回收异常退出的占用。
     *
     * @param conversationId 本次运行所属会话
     * @param pipelineRunId 本次Pipeline唯一运行ID
     * @return true表示取得执行权；false表示会话不存在或仍被占用
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
