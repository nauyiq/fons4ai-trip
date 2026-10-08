package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageHitl;

/**
 * 对话消息HITL扩展领域服务
 *
 * @author hongqy
 */
public interface ChatMessageHitlDomainService extends IService<ChatMessageHitl> {

    /**
     * 按旧状态进行条件更新，保证同一审批消息只会被一次恢复请求占用。
     *
     * @param messageId 消息ID
     * @param expectedStatus 预期的原状态
     * @param newStatus 需要更新的新状态
     * @return 是否更新成功
     */
    boolean changeStatus(String messageId, ChatMessageHitlStatus expectedStatus, ChatMessageHitlStatus newStatus);
}
