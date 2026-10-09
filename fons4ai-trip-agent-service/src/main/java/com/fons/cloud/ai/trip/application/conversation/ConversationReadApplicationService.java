package com.fons.cloud.ai.trip.application.conversation;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.request.PageMessageRequest;
import com.fons.cloud.ai.trip.common.vo.ConversationInfo;
import com.fons.cloud.ai.trip.common.vo.MessageInfo;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageHitl;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageHitlDomainService;
import com.fons.cloud.common.result.PageResult;
import com.fons.cloud.common.result.R;
import com.fons.cloud.db.common.PageWrappers;
import com.google.common.collect.Maps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话读取应用服务
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationReadApplicationService {
    private final ChatConversationDomainService chatConversationDomainService;
    private final ChatMessageDomainService chatMessageDomainService;
    private final ChatMessageHitlDomainService chatMessageHitlDomainService;

    /**
     * 获取用户会话列表
     * @param userId 用户id
     * @return
     */
    public R<List<ConversationInfo>> getConversationList(String userId) {
        List<ChatConversation> conversations = chatConversationDomainService.listByUserId(userId);
        return R.ok(conversations.stream().map(conversation -> ConversationInfo.builder()
                .id(conversation.getConversationId())
                .title(conversation.getTitle())
                .created(conversation.getCreated().getTime())
                .updated(conversation.getUpdated().getTime())
                .build()).toList());
    }

    /**
     * 分页查询会话消息列表
     * @param request
     * @return
     */
    public R<PageResult<MessageInfo>> pageQueryConversationMessages(PageMessageRequest request) {
        // 查询会话是否存在
        ChatConversation chatConversation = chatConversationDomainService.findByUseIdAndConversationId(request.userId(), request.conversationId());
        if (chatConversation == null) {
            return R.failed(TripAgentResultCode.CONVERSATION_NOT_EXIST);
        }

        // 查询会话消息记录
        Page<ChatMessage> page = chatMessageDomainService.pageQueryByConversationId(request.conversationId(), request.page(), request.pageSize());
        if (page == null || CollectionUtils.isEmpty(page.getRecords())) {
            return R.ok(new PageResult<>());
        }

        List<ChatMessage> records = page.getRecords();
        List<ChatMessageHitl> hitlList = records.stream().anyMatch(e -> e.getRole() == ChatRole.AGENT_HITL)
                ? chatMessageHitlDomainService.listByIds(records.stream().filter(e -> e.getRole() == ChatRole.AGENT_HITL).map(ChatMessage::getMessageId).toList())
                : List.of();
        Map<String, ChatMessageHitl> hitlMap = CollectionUtils.isEmpty(hitlList) ? new HashMap<>() : hitlList.stream().collect(Collectors.toMap(ChatMessageHitl::getMessageId, e -> e));

        PageResult<MessageInfo> pageResult = PageWrappers.buildResult(page, (chatMessage) -> {
            MessageInfo.MessageInfoBuilder builder = MessageInfo.builder()
                    .id(chatMessage.getMessageId())
                    .role(chatMessage.getRole().getCode())
                    .content(chatMessage.getContent())
                    .thinking(chatMessage.getThinking())
                    .messageContentType(chatMessage.getType().name())
                    .extra(StringUtils.isBlank(chatMessage.getExtra()) ? null : JSON.parseObject(chatMessage.getExtra()))
                    .feedback(chatMessage.getFeedback())
                    .feedbackAt(chatMessage.getFeedbackAt() == null ? null : chatMessage.getFeedbackAt().getTime());

            if (chatMessage.getRole() == ChatRole.AGENT_HITL) {
                ChatMessageHitl hitl = hitlMap.get(chatMessage.getMessageId());
                if (hitl != null) {
                    builder.hitlId(hitl.getHitlId());
                    builder.hitlStatus(hitl.getStatus().getCode());
                    builder.hitlData(StringUtils.isBlank(hitl.getHitlData()) ? null : JSON.parseObject(hitl.getHitlData()));
                }
            }
            return builder.build();
        });
        return R.ok(pageResult);

    }

}
