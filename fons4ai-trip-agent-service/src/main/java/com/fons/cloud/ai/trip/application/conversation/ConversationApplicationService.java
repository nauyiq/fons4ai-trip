package com.fons.cloud.ai.trip.application.conversation;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.agent.infrastructure.session.ActiveAgentSessionStore;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopInfo;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.request.AgentApprovalAction;
import com.fons.cloud.ai.agent.model.request.HitlRequestInfo;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.agent.core.TripAgent;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutePipeline;
import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.constants.TaskState;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.ai.trip.common.request.ConversationInterruptRequest;
import com.fons.cloud.ai.trip.common.request.ConversationReplayRequest;
import com.fons.cloud.ai.trip.common.request.ConversationStreamRequest;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageAggregate;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageHitl;
import com.fons.cloud.ai.trip.domain.entity.ChatRequestTrace;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageHitlDomainService;
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
    private final ChatMessageHitlDomainService chatMessageHitlDomainService;
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
        ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run = agentExecutePipeline.run(agentRequest, result -> Mono.fromRunnable(() -> handleChatResult(aggregate, result, null)));

        return streamRun(aggregate, run, null);
    }

    private Flux<String> streamRun(ChatMessageAggregate aggregate,
                                   ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run,
                                   String replayMessageId) {
        ChatRequestTrace trace = aggregate.getTrace();
        String taskId = trace.getTaskId();
        String traceRunId = trace.getRunId();
        String pipelineRunId = run.runId();
        return Flux.defer(() -> {
            ReactiveRunManager.Registration registered;
            try {
                registered = reactiveRunManager.register(taskId, run);
            } catch (Exception error) {
                updateTraceStateQuietly(traceRunId, TaskState.FAILED);
                releaseUnstartedApproval(replayMessageId);
                return Flux.error(error);
            }
            if (registered != ReactiveRunManager.Registration.REGISTERED) {
                // ALREADY_RUNNING 可能是同一请求的另一订阅，不能覆盖它正在维护的 Trace。
                updateTraceStateQuietly(traceRunId, registered == ReactiveRunManager.Registration.CANCELLED ? TaskState.INTERRUPT : TaskState.FAILED);
                releaseUnstartedApproval(replayMessageId);
                return Flux.error(SystemIntervalException.of("agent请求注册失败: " + registered));
            }
            try {
                updateTraceActive(aggregate.getConversationId(), traceRunId, pipelineRunId);
            } catch (Exception error) {
                try {
                    reactiveRunManager.release(taskId, pipelineRunId);
                } catch (Exception releaseError) {
                    log.error("[CHAT]释放Pipeline运行租约失败, taskId:{}, pipelineRunId:{}", taskId, pipelineRunId, releaseError);
                }
                updateTraceStateQuietly(traceRunId, TaskState.FAILED);
                releaseUnstartedApproval(replayMessageId);
                return Flux.error(error);
            }
            return chatStreamOutputAdapter.adapt(run, new CandidateOwner(aggregate.getConversation().getUserId(), aggregate.getConversationId()))
                    .doOnCancel(run::cancel) // 前端断开时也取消根 Run
                    .doFinally(signal -> {
                        try {
                            ReactiveTaskState state = run.state();
                            if (state == ReactiveTaskState.FAILED) {
                                updateTraceStateQuietly(traceRunId, TaskState.FAILED);
                            } else if (state == ReactiveTaskState.CANCELLED) {
                                updateTraceStateQuietly(traceRunId, TaskState.INTERRUPT);
                            }
                        } finally {
                            try {
                                reactiveRunManager.release(taskId, pipelineRunId);
                            } catch (Exception error) {
                                log.error("[CHAT]释放Pipeline运行租约失败, taskId:{}, pipelineRunId:{}", taskId, pipelineRunId, error);
                            }
                            try {
                                chatConversationDomainService.clearActiveRunIdIfMatch(aggregate.getConversationId(), pipelineRunId);
                            } catch (Exception error) {
                                log.error("[CHAT]清理会话活跃Pipeline运行ID失败, conversationId:{}, pipelineRunId:{}", aggregate.getConversationId(), pipelineRunId, error);
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
        if (result == ReactiveRunManager.CancelResult.NOT_FOUND) {
            log.warn("[CHAT]会话活跃Pipeline运行不存在，清理过期记录, conversationId:{}, pipelineRunId:{}", request.conversationId(), activeRunId);
            chatConversationDomainService.clearActiveRunIdIfMatch(request.conversationId(), activeRunId);
        }
        return R.success();
    }

    /**
     * 会话回复，  用户对Agent-hitl的回复
     * <p>
     * 只处理{@link HumanInTheLoopKind#APPROVAL}。INPUT_REQUIRED 应使用 stream 发送用户实际补充内容。
     * </p>
     *
     * @param request
     * @return
     */
    public Flux<String> replay(@Valid ConversationReplayRequest request) {
        return Flux.defer(() -> {
            log.info("[REPLAY]收到审批回复, userId:{}, conversationId:{}, messageId:{}, action:{}",
                    request.getUserId(), request.getConversationId(), request.getMessageId(), request.getAction());
            ChatConversation conversation = chatConversationDomainService.findByUseIdAndConversationId(
                    request.getUserId(), request.getConversationId());
            if (conversation == null) {
                return Flux.error(new BusinessRuntimeException(TripAgentResultCode.CONVERSATION_NOT_EXIST));
            }
            ChatMessage approval = chatMessageDomainService.findHitlMessage(
                    conversation.getConversationId(), request.getMessageId());
            ChatMessageHitl hitl = approval == null ? null : chatMessageHitlDomainService.getById(approval.getMessageId());
            if (approval == null || hitl == null) {
                return Flux.error(new BusinessRuntimeException(TripAgentResultCode.HITL_MESSAGE_NOT_EXIST));
            }
            if (StringUtils.isAnyBlank(hitl.getHitlId(), hitl.getOriginRunId(), hitl.getCheckpointId())) {
                return Flux.error(SystemIntervalException.of("人工审批恢复信息不完整"));
            }
            if (request.getAction() == AgentApprovalAction.EDIT &&
                    (request.getParams() == null || request.getParams().isEmpty())) {
                return Flux.error(new BusinessRuntimeException(TripAgentResultCode.HITL_EDIT_PARAMS_REQUIRED));
            }
            ChatMessageAggregate aggregate;
            try {
                // 审批占用、恢复Trace和用户回复必须原子落库，避免遗留孤立的RESUMING状态。
                aggregate = persistReplayRequest(conversation, request, approval.getMessageId());
            } catch (Exception error) {
                return Flux.error(error);
            }
            try {
                HitlRequestInfo hitlRequestInfo = HitlRequestInfo.builder()
                        .hitlId(hitl.getHitlId())
                        .humanInTheLoopKind(HumanInTheLoopKind.APPROVAL)
                        .originRunId(hitl.getOriginRunId())
                        .checkpointId(hitl.getCheckpointId())
                        .decision(request.getAction())
                        .params(request.getParams())
                        .build();
                AgentRequest agentRequest = AgentRequest.builder()
                        .userId(request.getUserId())
                        .conversationId(conversation.getConversationId())
                        .runId(aggregate.gerRunId())
                        .hitlRequestInfo(hitlRequestInfo)
                        .build();
                ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run = agentExecutePipeline.run(agentRequest,
                        result -> Mono.fromRunnable(() -> handleChatResult(aggregate, result, approval.getMessageId())));
                return streamRun(aggregate, run, approval.getMessageId());
            } catch (Exception error) {
                releaseUnstartedApproval(approval.getMessageId());
                return Flux.error(error);
            }
        });
    }

    private ChatMessageAggregate persistReplayRequest(ChatConversation conversation,
                                                      ConversationReplayRequest request,
                                                      String approvalMessageId) {
        return transactionTemplate.execute(status -> {
            if (!chatMessageHitlDomainService.changeStatus(approvalMessageId,
                    ChatMessageHitlStatus.PENDING, ChatMessageHitlStatus.RESUMING)) {
                throw new BusinessRuntimeException(TripAgentResultCode.HITL_MESSAGE_ALREADY_USED);
            }
            ChatRequestTrace trace = ChatRequestTrace.create(conversation.getConversationId());
            Assert.isTrue(chatRequestTraceDomainService.save(trace), () -> SystemIntervalException.of("审批恢复Trace持久化失败"));
            ChatMessage message = ChatMessage.createUser(conversation.getConversationId(), trace.getRunId(),
                    ChatRole.USER_RESUME, ChatMessageRequest.builder()
                            .messageType(ChatMessageContentType.TEXT)
                            .content(request.getAction().name())
                            .build());
            message.setExtra(JSON.toJSONString(java.util.Map.of("approvalMessageId", request.getMessageId())));
            Assert.isTrue(chatMessageDomainService.save(message), () -> SystemIntervalException.of("审批恢复消息持久化失败"));
            return new ChatMessageAggregate(conversation, trace, List.of(message));
        });
    }

    private void releaseUnstartedApproval(String messageId) {
        if (messageId != null) {
            try {
                chatMessageHitlDomainService.changeStatus(messageId, ChatMessageHitlStatus.RESUMING, ChatMessageHitlStatus.PENDING);
            } catch (Exception error) {
                log.error("[REPLAY]释放未启动审批消息失败, messageId:{}", messageId, error);
            }
        }
    }

    private void handleChatResult(ChatMessageAggregate aggregate, AgentExecutePipeline.PipelineResult result, String replayMessageId) {
        log.info("[CHAT]开始处理Agent管道结果, runId:{}", aggregate.gerRunId());
        AgentRunResult masterResult = result.masterAgentResult();
        AgentRunState state = masterResult.getState();
        ChatRequestTrace trace = aggregate
                .setAnalysisContent(result.queryRewriteResult(), result.recognitionResult())
                .setTraceTools(masterResult.getCompleteInfo() == null ? null : masterResult.getCompleteInfo().getTools())
                .setTraceState(getTaskState(state))
                .getTrace();

        // 失败、取消和拒绝结果不一定有 completeInfo，仍需独立持久化 Trace。
        List<ChatMessage> chatMessages = switch (state) {
            case COMPLETED, WAITING_APPROVAL -> {
                ChatConversation conversation = aggregate.getConversation();
                String agentName = activeAgentSessionStore.getActiveAgent(conversation.getUserId(), conversation.getConversationId())
                        .orElse(TripAgent.MASTER_AGENT.getAgentName());
                yield ChatMessage.createAgent(agentName, masterResult);
            }
            case FAILED, TIMED_OUT, REJECTED, APPROVAL_REJECTED, CANCELLED -> List.of();
            case CREATED, RUNNING -> throw SystemIntervalException.of("Agent返回了非终态结果: " + state);
        };
        List<ChatMessageHitl> hitlDetails = state == AgentRunState.WAITING_APPROVAL
                ? createApprovalDetails(masterResult, chatMessages)
                : List.of();

        transactionTemplate.executeWithoutResult(status -> {
            try {
                if (!chatMessages.isEmpty()) {
                    Assert.isTrue(chatMessageDomainService.saveBatch(chatMessages), () -> SystemIntervalException.of("保存Agent回复消息失败"));
                }
                if (!hitlDetails.isEmpty()) {
                    Assert.isTrue(chatMessageHitlDomainService.saveBatch(hitlDetails), () -> SystemIntervalException.of("保存Agent审批扩展信息失败"));
                }
                Assert.isTrue(chatRequestTraceDomainService.updateById(trace), () -> SystemIntervalException.of("更新聊天请求轨迹失败"));
                if (replayMessageId != null) {
                    Assert.isTrue(chatMessageHitlDomainService.changeStatus(replayMessageId, ChatMessageHitlStatus.RESUMING, ChatMessageHitlStatus.CONSUMED), () -> SystemIntervalException.of("审批消息状态更新失败"));
                }
            } catch (Exception e) {
                status.setRollbackOnly();
                throw e;
            }
        });
        log.info("[CHAT]保存Agent结果完成, runId:{}, state:{}, messageCount:{}", aggregate.gerRunId(), state, chatMessages.size());
    }

    private List<ChatMessageHitl> createApprovalDetails(AgentRunResult result, List<ChatMessage> messages) {
        Assert.notEmpty(result.getHumanInTheLoopInfos(),
                () -> SystemIntervalException.of("Agent审批结果缺少审批凭证"));
        List<HumanInTheLoopInfo> approvals = result.getHumanInTheLoopInfos().stream()
                .filter(info -> info.getKind() == HumanInTheLoopKind.APPROVAL)
                .toList();
        List<ChatMessage> approvalMessages = messages.stream()
                .filter(message -> message.getRole() == ChatRole.AGENT_HITL)
                .toList();
        Assert.isTrue(!approvals.isEmpty() && approvals.size() == approvalMessages.size(),
                () -> SystemIntervalException.of("Agent审批消息与恢复信息数量不一致"));
        for (HumanInTheLoopInfo approval : approvals) {
            Assert.isTrue(StringUtils.isNotBlank(approval.getId()) && StringUtils.isNotBlank(approval.getOriginRunId()),
                    () -> SystemIntervalException.of("Agent审批凭证缺少恢复标识"));
        }
        return java.util.stream.IntStream.range(0, approvals.size())
                .mapToObj(index -> ChatMessageHitl.create(
                        approvalMessages.get(index).getMessageId(), approvals.get(index)))
                .toList();
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
     * @param traceRunId 请求追踪 ID
     * @param pipelineRunId 运行管理器 ID
     */
    private void updateTraceActive(String conversationId, String traceRunId, String pipelineRunId) {
        transactionTemplate.execute(status -> {
            Assert.isTrue(chatRequestTraceDomainService.updateState(traceRunId, TaskState.PROCESS), () -> SystemIntervalException.of("更新trace状态失败"));
            Assert.isTrue(chatConversationDomainService.updateActiveRunId(conversationId, pipelineRunId), () -> SystemIntervalException.of("更新会话活跃Pipeline运行ID失败"));
            return null;
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
