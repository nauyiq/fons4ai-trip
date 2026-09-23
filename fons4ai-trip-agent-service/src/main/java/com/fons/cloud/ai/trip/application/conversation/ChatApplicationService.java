package com.fons.cloud.ai.trip.application.conversation;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.agent.infrastructure.session.ActiveAgentSessionStore;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.agent.core.TripAgent;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutePipeline;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.constants.TaskState;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.ai.trip.common.request.ChatRequest;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageAggregate;
import com.fons.cloud.ai.trip.domain.entity.ChatRequestTrace;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatRequestTraceDomainService;
import com.fons.cloud.ai.trip.infrastructure.converter.ChatMessageConverter;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import com.fons.cloud.reactor.api.ReactiveTaskRun;
import com.fons.cloud.reactor.core.ReactiveRunManager;
import com.fons.cloud.reactor.model.ReactiveTaskState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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
    private final ChatMessageConverter chatMessageConverter;
    private final ActiveAgentSessionStore activeAgentSessionStore;
    private final AgentExecutePipeline agentExecutePipeline;
    private final ChatStreamOutputAdapter chatStreamOutputAdapter;
    private final ReactiveRunManager reactiveRunManager;

    private final TransactionTemplate transactionTemplate;
    private final ChatConversationDomainService chatConversationDomainService;
    private final ChatRequestTraceDomainService chatRequestTraceDomainService;
    private final ChatMessageDomainService chatMessageDomainService;


    /**
     * 发起一次聊天请求
     * @param request
     * @return
     */
    public Flux<String> chat(ChatRequest request) {
        String userId = request.getUserId();
        String sessionId = request.getSessionId();
        List<ChatMessageRequest> messages = request.getMessages();
        log.info("[CHAT]接收到一次请求, userId:{}, sessionId:{}, messageCount:{}", userId, sessionId, messages.size());

        // 持久化聊天请求
        ChatMessageAggregate aggregate = persistChatRequest(request);
        Assert.notNull(aggregate, () -> SystemIntervalException.of(ResultCode.SYSTEM_BUSY.getMessage()));

        // 构建Agent请求
        AgentRequest agentRequest = buildAgentRequest(request, aggregate.getMessages());
        ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run = agentExecutePipeline.run(agentRequest, result -> {
            // 处理响应结果
            return Mono.fromRunnable(() -> handleChatResult(aggregate, result));
        });

        String taskId = "trip:trace:" + aggregate.getTrace().getTraceId();

        return Flux.defer(() -> {
            ReactiveRunManager.Registration registered;
            try {
                registered = reactiveRunManager.register(taskId, run);
            } catch (Exception error) {
                updateTraceStateQuietly(aggregate.getTrace().getTraceId(), TaskState.FAILED);
                return Flux.error(error);
            }
            if (registered != ReactiveRunManager.Registration.REGISTERED) {
                // ALREADY_RUNNING 可能是同一请求的另一订阅，不能覆盖它正在维护的 Trace。
                if (registered == ReactiveRunManager.Registration.CANCELLED) {
                    updateTraceStateQuietly(aggregate.getTrace().getTraceId(), TaskState.INTERRUPT);
                }
                return Flux.error(SystemIntervalException.of("agent请求注册失败: " + registered));
            }
            updateTraceStateQuietly(aggregate.getTrace().getTraceId(), TaskState.PROCESS);
            return chatStreamOutputAdapter.adapt(run)
                    .doOnCancel(run::cancel) // 前端断开时也取消根 Run
                    .doFinally(signal -> {
                        try {
                            ReactiveTaskState state = run.state();
                            if (state == ReactiveTaskState.FAILED) {
                                updateTraceStateQuietly(aggregate.getTrace().getTraceId(), TaskState.FAILED);
                            } else if (state == ReactiveTaskState.CANCELLED) {
                                updateTraceStateQuietly(aggregate.getTrace().getTraceId(), TaskState.INTERRUPT);
                            }
                        } finally {
                            try {
                                reactiveRunManager.release(taskId, run.runId());
                            } catch (Exception error) {
                                log.error("[CHAT]释放Pipeline运行租约失败, taskId:{}, pipelineRunId:{}",
                                        taskId, run.runId(), error);
                            }
                        }
                    });
        });
    }


    private void handleChatResult(ChatMessageAggregate aggregate, AgentExecutePipeline.PipelineResult result) {
        log.info("[CHAT]开始处理Agent管道结果, runId:{}", aggregate.gerRunId());
        try {
            // 处理Agent回复
            AgentRunResult masterResult = result.masterAgentResult();
            AgentRunState state = masterResult.getState();
            // 获取trace状态
            TaskState taskState = getTaskState(state);
            ChatRequestTrace trace = aggregate
                    .setAnalysisContent(result.queryRewriteResult(), result.recognitionResult())
                    .setTraceTools(masterResult.getCompleteInfo() == null ? null : masterResult.getCompleteInfo().getTools())
                    .setTraceState(taskState)
                    .getTrace();
            ChatConversation conversation = aggregate.getConversation();

            // 构造Agent回复消息
            ChatMessage chatMessage = ChatMessage.replay(activeAgentSessionStore.getActiveAgent(conversation.getUserId(), conversation.getConversationId()).orElse(TripAgent.MASTER_AGENT.getAgentName()), masterResult);
            Boolean execute = transactionTemplate.execute(status -> {
                try {
                    Assert.isTrue(chatMessageDomainService.save(chatMessage), () -> SystemIntervalException.of(ResultCode.SYSTEM_BUSY.getMessage()));
                    Assert.isTrue(chatRequestTraceDomainService.updateById(trace), () -> SystemIntervalException.of(ResultCode.SYSTEM_BUSY.getMessage()));
                    return true;
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    status.setRollbackOnly();
                    return false;
                }
            });
            log.info("[CHAT]保存Agent消息回复完成, runId:{}, execute:{}", aggregate.gerRunId(), execute);

        } catch (Exception e) {
            log.error("[CHAT]处理Agent结果失败, runId:{}", aggregate.gerRunId(), e);
        }
    }

    @NotNull
    private static TaskState getTaskState(AgentRunState state) {
        return switch (state) {
            case CREATED -> TaskState.init;
            case RUNNING -> TaskState.PROCESS;
            case WAITING_APPROVAL -> TaskState.WAITING_APPROVAL;
            case COMPLETED -> TaskState.SUCCESS;
            case CANCELLED -> TaskState.INTERRUPT;
            case FAILED, TIMED_OUT, REJECTED, APPROVAL_REJECTED -> TaskState.FAILED;
        };
    }

    /** Trace 状态写入是观测性后置动作，失败不能覆盖原有事件流或阻断租约释放。 */
    private void updateTraceStateQuietly(Long traceId, TaskState state) {
        try {
            if (!chatRequestTraceDomainService.updateState(traceId, state)) {
                log.warn("[CHAT]更新Trace状态未命中记录, traceId:{}, state:{}", traceId, state);
            }
        } catch (Exception error) {
            log.error("[CHAT]更新Trace状态失败, traceId:{}, state:{}", traceId, state, error);
        }
    }


    /**
     * 持久化一次聊天请求
     *
     * @param request
     * @return
     */
    private ChatMessageAggregate persistChatRequest(ChatRequest request) {
        // 获取会话并持久化请求
        ChatConversation conversation = chatConversationDomainService.findByUseIdAndConversationId(request.getUserId(), request.getSessionId());
        boolean isNewConversation = conversation == null;
        if (isNewConversation) {
            conversation = ChatConversation.create(request.getUserId(), request.getSessionId());
        }
        // 创建聊天请求trace
        ChatRequestTrace trace = ChatRequestTrace.create(conversation.getConversationId());

        ChatConversation finalConversation = conversation;
        return transactionTemplate.execute(status -> {
            try {
                if (isNewConversation) {
                    Assert.isTrue(chatConversationDomainService.save(finalConversation), () -> SystemIntervalException.of("会话Conversation持久化失败"));
                }
                Assert.isTrue(chatRequestTraceDomainService.save(trace), () -> SystemIntervalException.of("聊天请求轨迹持久化失败"));

                List<ChatMessage> chatMessages = request.getMessages().stream()
                        .map(e -> ChatMessage.create(finalConversation.getConversationId(), trace.getRunId(),  ChatRole.USER, e))
                        .toList();
                Assert.isTrue(chatMessageDomainService.saveBatch(chatMessages), () -> SystemIntervalException.of("聊天消息持久化失败"));

                return new ChatMessageAggregate(finalConversation, trace, chatMessages);
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
