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
import com.fons.cloud.ai.trip.domain.entity.ChatRequestTrace;
import com.fons.cloud.ai.trip.domain.service.ChatRequestTraceDomainService;
import com.fons.cloud.ai.trip.common.vo.RequestTraceInfo;
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
    private final ChatRequestTraceDomainService chatRequestTraceDomainService;

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
        List<String> runIds = records.stream().map(ChatMessage::getRunId).filter(StringUtils::isNotBlank).distinct().toList();
        // 会话归属已校验，再按本页 Run 批量关联，避免每条消息一次查询或跨会话读取。
        Map<String, RequestTraceInfo> traces = runIds.isEmpty() ? Map.of()
                : chatRequestTraceDomainService.lambdaQuery()
                        .eq(ChatRequestTrace::getConversationId, request.conversationId())
                        .in(ChatRequestTrace::getRunId, runIds).list().stream()
                        .collect(Collectors.toMap(ChatRequestTrace::getRunId, RequestTraceInfo::from));
        List<ChatMessageHitl> hitlList = records.stream().anyMatch(e -> e.getRole() == ChatRole.AGENT_HITL)
                ? chatMessageHitlDomainService.listByIds(records.stream().filter(e -> e.getRole() == ChatRole.AGENT_HITL).map(ChatMessage::getMessageId).toList())
                : List.of();
        Map<String, ChatMessageHitl> hitlMap = CollectionUtils.isEmpty(hitlList) ? new HashMap<>() : hitlList.stream().collect(Collectors.toMap(ChatMessageHitl::getMessageId, e -> e));

        PageResult<MessageInfo> pageResult = PageWrappers.buildResult(page, (chatMessage) -> {
            MessageInfo.MessageInfoBuilder builder = MessageInfo.builder()
                    .id(chatMessage.getMessageId())
                    .runId(chatMessage.getRunId())
                    .requestTrace(traces.get(chatMessage.getRunId()))
                    .role(chatMessage.getRole().getCode())
                    .content(chatMessage.getContent())
                    .agentName(chatMessage.getAgentName())
                    .created(chatMessage.getCreated() == null ? null : chatMessage.getCreated().getTime())
                    .thinking(chatMessage.getThinking())
                    .messageContentType(chatMessage.getType().name())
                    .extra(readMessageExtra(chatMessage))
                    .feedback(chatMessage.getFeedback())
                    .feedbackAt(chatMessage.getFeedbackAt() == null ? null : chatMessage.getFeedbackAt().getTime());

            if (chatMessage.getRole() == ChatRole.AGENT_HITL) {
                ChatMessageHitl hitl = hitlMap.get(chatMessage.getMessageId());
                if (hitl != null) {
                    builder.hitlId(hitl.getHitlId());
                    builder.pauseId(hitl.getPauseId());
                    builder.pauseVersion(hitl.getPauseVersion());
                    builder.resumeRef(hitl.getResumeRef());
                    builder.resumeRequestId(hitl.getResumeRequestId());
                    builder.resumeRunId(hitl.getResumeRunId());
                    builder.hitlStatus(hitl.getStatus().getCode());
                    builder.hitlData(StringUtils.isBlank(hitl.getHitlData()) ? null : JSON.parseObject(hitl.getHitlData()));
                }
            }
            return builder.build();
        });
        return R.ok(pageResult);

    }

    /** 非权威展示元数据损坏不能阻断整页历史；审批票证仍走独立的可信校验路径。 */
    private Map<String, Object> readMessageExtra(ChatMessage message) {
        if (StringUtils.isBlank(message.getExtra())) {
            return null;
        }
        try { return JSON.parseObject(message.getExtra()); }
        catch (RuntimeException error) {
            log.warn("消息扩展内容无法解析，忽略展示元数据，messageId:{}", message.getMessageId());
            return null;
        }
    }

}
