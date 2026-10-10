package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageHitlMapper;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageMapper;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author hongqy
 */
@Service
@RequiredArgsConstructor
public class ChatMessageDomainServiceImpl extends ServiceImpl<ChatMessageMapper, ChatMessage> implements ChatMessageDomainService {
    private final ChatMessageHitlMapper chatMessageHitlMapper;

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
    public ChatMessage findHitlMessage(String conversationId, String messageId) {
        return getOne(Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getMessageId, messageId)
                .eq(ChatMessage::getRole, ChatRole.AGENT_HITL)
        );
    }

    @Override
    public Page<ChatMessage> pageQueryByConversationId(String conversationId, int page, int pageSize) {
        Page<ChatMessage> pageQuery = new Page<>(page, pageSize);
        return this.page(pageQuery, Wrappers.lambdaQuery(ChatMessage.class)
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByAsc(ChatMessage::getCreated, ChatMessage::getMessageId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeByConversationId(String conversationId) {
        boolean update = update(Wrappers.lambdaUpdate(ChatMessage.class)
                .eq(ChatMessage::getConversationId, conversationId)
                .set(ChatMessage::getDeleted, true));

        if (update) {
            List<ChatMessage> hitlMessages = list(Wrappers.lambdaQuery(ChatMessage.class)
                    .eq(ChatMessage::getConversationId, conversationId)
                    .eq(ChatMessage::getRole, ChatRole.AGENT_HITL));
            if (CollectionUtils.isNotEmpty(hitlMessages)) {
               return chatMessageHitlMapper.deleteBatchIds(hitlMessages.stream().map(ChatMessage::getMessageId).collect(Collectors.toList())) > 0;
            }
        }

        return update;
    }
}
