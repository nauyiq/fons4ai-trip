package com.fons.cloud.ai.trip.application.conversation;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.ai.trip.common.request.ChatRequest;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import com.fons.cloud.ai.trip.infrastructure.converter.ChatMessageConverter;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 聊天服务应用层, Agent核心入口
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatApplicationService {

    private final TransactionTemplate transactionTemplate;
    private final ChatMessageDomainService chatMessageDomainService;
    private final ChatConversationDomainService chatConversationDomainService;

    private final ChatMessageConverter chatMessageConverter;


    /**
     * 发起一次聊天请求
     *
     * @param request
     * @return
     */
    public Flux<String> chat(ChatRequest request) {
        String userId = request.getUserId();
        String sessionId = request.getSessionId();
        List<ChatMessageRequest> messages = request.getMessages();
        log.info("[CHAT]接收到一次Agent请求, userId:{}, sessionId:{}, messageCount:{}", userId, sessionId, messages.size());

        // 获取会话并持久化请求
        ChatConversation conversation = chatConversationDomainService.findByUseIdAndConversationId(userId, request.getSessionId());
        List<ChatMessage> chatMessages = persistChatRequest(conversation, request);
        Assert.notNull(conversation, () -> SystemIntervalException.of(ResultCode.SYSTEM_BUSY.getMessage()));
        Assert.notEmpty(chatMessages, () -> SystemIntervalException.of(ResultCode.SYSTEM_BUSY.getMessage()));

        // 构建Agent请求
        AgentRequest agentRequest = buildAgentRequest(request, chatMessages);

        return null;

    }



    /**
     * 持久化一次聊天请求
     *
     * @param conversation
     * @param request
     * @return
     */
    private List<ChatMessage> persistChatRequest(ChatConversation conversation, ChatRequest request) {
        boolean isNewConversation = conversation == null;
        if (isNewConversation) {
            conversation = ChatConversation.create(request.getUserId(), request.getSessionId());
        }
        ChatConversation finalConversation = conversation;
        return transactionTemplate.execute(status -> {
            try {
                if (isNewConversation) {
                    Assert.isTrue(chatConversationDomainService.save(finalConversation), () -> SystemIntervalException.of("会话Conversation持久化失败"));
                }
                List<ChatMessage> chatMessages = request.getMessages().stream()
                        .map(e -> ChatMessage.create(finalConversation.getConversationId(), ChatRole.USER, e))
                        .toList();
                Assert.isTrue(chatMessageDomainService.saveBatch(chatMessages), () -> SystemIntervalException.of("聊天消息持久化失败"));
                log.info("已创建新会话， 会话Id:{}", finalConversation.getConversationId());
                return chatMessages;
            } catch (Exception e) {
                log.error(e.getMessage(), e);
                status.setRollbackOnly();
                return null;
            }
        });
    }


    /**
     * 构建Agent请求
     *
     * @param request
     * @return
     */
    private AgentRequest buildAgentRequest(ChatRequest request, List<ChatMessage> messages) {
        ChatMessage first = messages.getFirst();
        return AgentRequest.builder()
                .userId(request.getUserId())
                .conversationId(request.getSessionId())
                .runId(first.getRunId())
                .contents(messages.stream().map(chatMessageConverter::convertAgentInputContent).toList())
                .build();
    }

}
