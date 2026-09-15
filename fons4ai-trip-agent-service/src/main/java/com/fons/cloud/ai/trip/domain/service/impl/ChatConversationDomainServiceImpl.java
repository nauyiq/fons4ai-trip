package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;
import com.fons.cloud.ai.trip.domain.mapper.ChatConversationMapper;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class ChatConversationDomainServiceImpl extends ServiceImpl<ChatConversationMapper, ChatConversation> implements ChatConversationDomainService {

    @Override
    public ChatConversation findByUseIdAndConversationId(String useId, String conversationId) {
        return getOne(Wrappers.lambdaQuery(ChatConversation.class)
                .eq(ChatConversation::getUserId, useId)
                .eq(ChatConversation::getConversationId, conversationId));
    }
}
