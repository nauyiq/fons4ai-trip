package com.fons.cloud.ai.trip.application.conversation;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.agent.infrastructure.session.ActiveAgentSessionStore;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.agent.core.TripAgent;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutePipeline;
import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.constants.TaskState;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.ai.trip.common.request.ConversationInterruptRequest;
import com.fons.cloud.ai.trip.common.request.ConversationReplayRequest;
import com.fons.cloud.ai.trip.common.request.ConversationStreamRequest;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageAggregate;
import com.fons.cloud.ai.trip.domain.entity.ChatRequestTrace;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatRequestTraceDomainService;
import com.fons.cloud.ai.trip.infrastructure.converter.ChatMessageConverter;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import com.fons.cloud.reactor.api.ReactiveTaskRun;
import com.fons.cloud.reactor.core.ReactiveRunManager;
import com.fons.cloud.reactor.model.ReactiveTaskState;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallbackWithoutResult;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 会话服务应用层, Agent核心入口
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationApplicationService {
    private final ChatMessageConverter chatMessageConverter;
    private final ActiveAgentSessionStore activeAgentSessionStore;
    private final AgentExecutePipeline agentExecutePipeline;
    private final ChatStreamOutputAdapter chatStreamOutputAdapter;
    private final ReactiveRunManager reactiveRunManager;

    private final TransactionTemplate transactionTemplate;
    private final ChatMessageDomainService chatMessageDomainService;
    private final ChatConversationDomainService chatConversationDomainService;
    private final ChatRequestTraceDomainService chatRequestTraceDomainService;

    /**
     * 发起一次聊天请求, 流式输出
     * <p>
     * 1. 如果传入的conversationId在库里不存在 会默认创建新会话
     * 2. 如果接收的是工具HITL， 禁止调用该方法进行中断回复 而是应该使用#replay 方法
     * </p>
     *
     * @param request
     * @return
     */
    public Flux<String> stream(ConversationStreamRequest request) {
        String userId = request.getUserId();
        List<ChatMessageRequest> messages = request.getMessages();
        log.info("[CHAT]接收到一次请求, userId:{}, conversationId:{}, messageCount:{}", userId, request.getConversationId(), messages.size());

        // 持久化聊天请求
        ChatMessageAggregate aggregate = persistChatRequest(request);
        Assert.notNull(aggregate, () -> SystemIntervalException.of(ResultCode.SYSTEM_BUSY.getMessage()));

        // 构建Agent请求
        AgentRequest agentRequest = buildAgentRequest(request, aggregate);
        ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run = agentExecutePipeline.run(agentRequest, result -> {
            // 处理响应结果
            return Mono.fromRunnable(() -> handleChatResult(aggregate, result));
        });

        ChatRequestTrace trace = aggregate.getTrace();
        String taskId = trace.getTaskId();
        String runId = trace.getRunId();
        return Flux.defer(() -> {
            ReactiveRunManager.Registration registered;
            try {
                registered = reactiveRunManager.register(taskId, run);
            } catch (Exception error) {
                updateTraceStateQuietly(runId, TaskState.FAILED);
                return Flux.error(error);
            }
            if (registered != ReactiveRunManager.Registration.REGISTERED) {
                // ALREADY_RUNNING 可能是同一请求的另一订阅，不能覆盖它正在维护的 Trace。
                if (registered == ReactiveRunManager.Registration.CANCELLED) {
                    updateTraceStateQuietly(runId, TaskState.INTERRUPT);
                }
                return Flux.error(SystemIntervalException.of("agent请求注册失败: " + registered));
            }
            updateTraceActive(aggregate.getConversationId(), runId);
            return chatStreamOutputAdapter.adapt(run, aggregate.getConversationId())
                    .doOnCancel(run::cancel) // 前端断开时也取消根 Run
                    .doFinally(signal -> {
                        try {
                            ReactiveTaskState state = run.state();
                            if (state == ReactiveTaskState.FAILED) {
                                updateTraceStateQuietly(runId, TaskState.FAILED);
                            } else if (state == ReactiveTaskState.CANCELLED) {
                                updateTraceStateQuietly(runId, TaskState.INTERRUPT);
                            }
                        } finally {
                            try {
                                reactiveRunManager.release(taskId, run.runId());
                            } catch (Exception error) {
                                log.error("[CHAT]释放Pipeline运行租约失败, taskId:{}, pipelineRunId:{}", taskId, run.runId(), error);
                            }
                        }
                    });
        });
    }

    /**
     * 中断正在执行任务的Agent
     * <p>
     * 如果不存在正在执行的Agent时 也正常返回成功
     * </p>
     *
     * @param request
     * @return
     */
    public R<Void> interrupt(ConversationInterruptRequest request) {
        // 查询会话是否存在
        ChatConversation conversation = chatConversationDomainService.findByUseIdAndConversationId(request.userId(), request.conversationId());
        if (conversation == null) {
            return R.failed(TripAgentResultCode.CONVERSATION_NOT_EXIST);
        }
        String activeRunId = conversation.getActiveRunId();
        if (StringUtils.isEmpty(activeRunId)) {
            // 不存在也照样返回成功
            log.warn("[CHAT]会话[{}]当前没有正在执行的任务", request.conversationId());
            return R.success();
        }
        // 直接中断任务
        ReactiveRunManager.CancelResult result = reactiveRunManager.cancel(conversation.getTaskId(), activeRunId);
        if (result == ReactiveRunManager.CancelResult.FAILED) {
            return R.failed(ResultCode.SYSTEM_BUSY);
        }
        return R.success();
    }

    /**
     * 会话回复，  用户对Agent-hitl的回复
     * <p>
     * 只处理{@link HumanInTheLoopKind#APPROVAL}的请求， 如果原生HITL不存在 都会降级走普通请求，
     * 让LLM于用户对话进行需求澄清等
     * </p>
     *
     * @param request
     * @return
     */
    public Flux<String> replay(@Valid ConversationReplayRequest request) {
        log.info("[REPLAY]接收到一次回复请求, request:{}", JSON.toJSONString(request));
        // 查找会话
        ChatConversation conversation = chatConversationDomainService.findByUseIdAndConversationId(request.getUserId(), request.getConversationId());
        if (conversation == null) {
            return Flux.error(new BusinessRuntimeException(TripAgentResultCode.CONVERSATION_NOT_EXIST));
        }

        // 查询当前会话下最后一条Agent回复的消息
        ChatMessage hitlMessages = chatMessageDomainService.findHitlMessages(request.getConversationId(), request.getOriginRunId(), request.getHitlId());
        if (hitlMessages == null) {
            // 不存在人工审批消息， 无法进行中断回复 构建普通消息请求  防止人工审批消息没落库而用户回复了却返回系统异常 做一次业务兼容
            log.info("[REPLAY]获取人工审批消息不存在， 进行业务降级发起普通消息请求。 hitlId:{}", request.getHitlId());
            ConversationStreamRequest streamRequest = ConversationStreamRequest.builder()
                    .userId(request.getUserId())
                    .conversationId(request.getConversationId())
                    .messages(List.of(ChatMessageRequest.builder().messageType(ChatMessageContentType.TEXT).content(request.getAction().name()).build()))
                    .build();
            return this.stream(streamRequest);
        } else {
            // TODO 中断回复
            return null;
        }


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
            List<ChatMessage> chatMessages = ChatMessage.createAgent(activeAgentSessionStore.getActiveAgent(conversation.getUserId(), conversation.getConversationId()).orElse(TripAgent.MASTER_AGENT.getAgentName()), masterResult);
            Boolean execute = transactionTemplate.execute(status -> {
                try {
                    Assert.isTrue(chatMessageDomainService.saveBatch(chatMessages), () -> SystemIntervalException.of(ResultCode.SYSTEM_BUSY.getMessage()));
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

    /**
     * 更新目前trace的活跃状态
     *
     * @param conversationId
     * @param runId
     */
    private void updateTraceActive(String conversationId, String runId) {
        transactionTemplate.execute(new TransactionCallbackWithoutResult() {
            @Override
            protected void doInTransactionWithoutResult(TransactionStatus status) {
                try {
                    Assert.isTrue(chatRequestTraceDomainService.updateState(runId, TaskState.PROCESS), () -> SystemIntervalException.of("更新trace状态失败"));
                    Assert.isTrue(chatConversationDomainService.updateActiveRunId(conversationId, runId), () -> SystemIntervalException.of("更新会话活跃RunId失败"));
                } catch (Exception e) {
                    log.error("[CHAT]更新活跃的Trace状态失败, runId:{}", runId, e);
                }
            }
        });
    }

    /**
     * Trace 状态写入是观测性后置动作，失败不能覆盖原有事件流或阻断租约释放。
     */
    private void updateTraceStateQuietly(String runId, TaskState state) {
        try {
            if (!chatRequestTraceDomainService.updateState(runId, state)) {
                log.warn("[CHAT]更新Trace状态未命中记录, runId:{}, state:{}", runId, state);
            }
        } catch (Exception error) {
            log.error("[CHAT]更新Trace状态失败, runId:{}, state:{}", runId, state, error);
        }
    }


    /**
     * 持久化一次聊天请求
     *
     * @param request
     * @return
     */
    private ChatMessageAggregate persistChatRequest(ConversationStreamRequest request) {
        // 获取会话并持久化请求
        boolean isNewConversation;
        ChatConversation conversation;
        if (StringUtils.isBlank(request.getConversationId())) {
            isNewConversation = true;
            conversation = ChatConversation.create(request.getUserId());
        } else {
            isNewConversation = false;
            conversation = chatConversationDomainService.findByUseIdAndConversationId(request.getUserId(), request.getConversationId());
            Assert.notNull(conversation, () -> BusinessRuntimeException.of(TripAgentResultCode.CONVERSATION_NOT_EXIST));
        }

        ChatConversation finalConversation = conversation;
        return transactionTemplate.execute(status -> {
            try {
                if (isNewConversation) {
                    Assert.isTrue(chatConversationDomainService.save(finalConversation), () -> SystemIntervalException.of("会话Conversation持久化失败"));
                }

                ChatRequestTrace trace = ChatRequestTrace.create(conversation.getConversationId());
                Assert.isTrue(chatRequestTraceDomainService.save(trace), () -> SystemIntervalException.of("聊天请求轨迹持久化失败"));

                List<ChatMessage> chatMessages = request.getMessages().stream()
                        .map(e -> ChatMessage.createUser(finalConversation.getConversationId(), trace.getRunId(), ChatRole.USER, e))
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
     * @param aggregate 已持久化的会话及消息
     * @return
     */
    private AgentRequest buildAgentRequest(ConversationStreamRequest request, ChatMessageAggregate aggregate) {
        List<ChatMessage> messages = aggregate.getMessages();
        ChatMessage first = messages.getFirst();
        return AgentRequest.builder()
                .userId(request.getUserId())
                .conversationId(aggregate.getConversationId())
                .runId(first.getRunId())
                .contents(messages.stream().map(chatMessageConverter::convertAgentInputContent).toList())
                .build();
    }


}
