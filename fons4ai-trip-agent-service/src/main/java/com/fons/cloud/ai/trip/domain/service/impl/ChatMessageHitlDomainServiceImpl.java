package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageHitl;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageHitlMapper;
import com.fons.cloud.ai.trip.domain.service.ChatMessageHitlDomainService;
import org.springframework.stereotype.Service;

/**
 * 对话消息HITL扩展领域服务实现
 *
 * @author hongqy
 */
@Service
public class ChatMessageHitlDomainServiceImpl extends ServiceImpl<ChatMessageHitlMapper, ChatMessageHitl> implements ChatMessageHitlDomainService {

    @Override
    public boolean changeStatus(String messageId, ChatMessageHitlStatus expectedStatus, ChatMessageHitlStatus newStatus) {
        return update(Wrappers.lambdaUpdate(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getMessageId, messageId)
                .eq(ChatMessageHitl::getStatus, expectedStatus)
                .set(ChatMessageHitl::getStatus, newStatus));
    }
}
