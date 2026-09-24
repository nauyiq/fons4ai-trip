package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageMapper;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author hongqy
 */
@Service
public class ChatMessageDomainServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageDomainService {

    @Override
    public List<ChatMessage> findRecentMessages(String conversationId, String excludedRunId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        List<ChatMessage> recent = new ArrayList<>(list(Wrappers.lambdaQuery(ChatMessage.class)
                .eq(ChatMessage::getConversationId, conversationId)
                .ne(StringUtils.isNotBlank(excludedRunId), ChatMessage::getRunId, excludedRunId)
                .orderByDesc(ChatMessage::getCreated, ChatMessage::getMessageId)
                .last("LIMIT " + limit)));
        Collections.reverse(recent);
        return recent;
    }

    @Override
    public ChatMessage findHitlMessages(String conversationId, String runId, String hitlId) {
        return getOne(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getRunId, runId)
                .eq(ChatMessage::getRole, ChatRole.AGENT_HITL)
                .eq(ChatMessage::getContent, hitlId)
        );
    }
}
