package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageHitlMapper;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageMapper;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
                // 与实时发布使用同一就绪条件；SQL过滤也使分页总数不泄露准备中的审批。
                .apply("(role <> {0} OR EXISTS (SELECT 1 FROM chat_message_hitl h "
                                + "WHERE h.message_id = chat_message.message_id AND h.status <> {1}))",
                        ChatRole.AGENT_HITL.getCode(), ChatMessageHitlStatus.PREPARED.getCode())
                .orderByAsc(ChatMessage::getCreated, ChatMessage::getMessageId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeByConversationId(String conversationId) {
        // 先读取审批消息ID，逻辑删除之后的普通查询会自动排除这些消息。
        List<String> hitlMessageIds = list(Wrappers.lambdaQuery(ChatMessage.class)
                .eq(ChatMessage::getConversationId, conversationId)
                .eq(ChatMessage::getRole, ChatRole.AGENT_HITL)).stream()
                .map(ChatMessage::getMessageId).toList();
        update(Wrappers.lambdaUpdate(ChatMessage.class)
                .eq(ChatMessage::getConversationId, conversationId)
                .set(ChatMessage::getDeleted, true));

        if (!hitlMessageIds.isEmpty()) {
            chatMessageHitlMapper.deleteBatchIds(hitlMessageIds);
        }
        // 空会话或重复清理没有影响行也是成功；实际SQL异常由外层事务统一回滚。
        return true;
    }
}
