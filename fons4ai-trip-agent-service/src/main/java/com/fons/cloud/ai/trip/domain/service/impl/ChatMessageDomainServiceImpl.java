package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageMapper;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author hongqy
 */
@Service
public class ChatMessageDomainServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageDomainService {

    @Override
    public List<ChatMessage> findRecentMessages(String conversationId, int limit) {
        return list(Wrappers.lambdaQuery(ChatMessage.class)
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreated)
                .last("LIMIT " + limit));
    }
}
