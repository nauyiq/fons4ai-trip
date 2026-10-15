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
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.constants.TaskState;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.ai.trip.common.request.ConversationInterruptRequest;
import com.fons.cloud.ai.trip.common.request.ConversationReplayRequest;
import com.fons.cloud.ai.trip.common.request.ConversationStreamRequest;
import com.fons.cloud.ai.trip.common.vo.MessageInfo;
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
import com.fons.cloud.common.base.exception.BizException;
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
import reactor.core.scheduler.Schedulers;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

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
                                   ApprovalReplay replay) {
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
                releaseUnstartedApproval(replay);
                return Flux.error(error);
            }
            if (registered != ReactiveRunManager.Registration.REGISTERED) {
                // ALREADY_RUNNING 可能是同一请求的另一订阅，不能覆盖它正在维护的 Trace。
                updateTraceStateQuietly(traceRunId, registered == ReactiveRunManager.Registration.CANCELLED ? TaskState.INTERRUPT : TaskState.FAILED);
                releaseUnstartedApproval(replay);
                return Flux.error(SystemIntervalException.of("agent请求注册失败: " + registered));
            }
            try {
                if (replay == null) {
                    // 覆盖请求持久化到注册之间的窗口，不能趁上一批交接执行权时绕过审批。
                    requireNoPendingApproval(aggregate.getConversationId());
                }
                updateTraceActive(aggregate.getConversationId(), traceRunId, pipelineRunId);
            } catch (Exception error) {
                try {
                    reactiveRunManager.release(taskId, pipelineRunId);
                } catch (Exception releaseError) {
                    log.error("[CHAT]释放Pipeline运行租约失败, taskId:{}, pipelineRunId:{}", taskId, pipelineRunId, releaseError);
                }
                updateTraceStateQuietly(traceRunId, TaskState.FAILED);
                releaseUnstartedApproval(replay);
                return Flux.error(error);
            }
            return chatStreamOutputAdapter.adapt(run,
                            new CandidateOwner(aggregate.getConversation().getUserId(), aggregate.getConversationId()),
                            result -> finalizeRunResult(aggregate, result))
                    .doOnCancel(run::cancel) // 前端断开时也取消根 Run
                    .doFinally(signal -> {
                        try {
                            ReactiveTaskState state = run.state();
                            if (state == ReactiveTaskState.FAILED) {
                                updateTraceStateQuietly(traceRunId, TaskState.FAILED);
                                failReplayQuietly(replay);
                            } else if (state == ReactiveTaskState.CANCELLED) {
                                updateTraceStateQuietly(traceRunId, TaskState.INTERRUPT);
                                failReplayQuietly(replay);
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
     * 一次提交同一批次的全部审批决定，只创建一个恢复Pipeline和Agent Run。
     * <p>
     * 原生状态及编辑参数先整体校验，再事务占用全部票证并保存一个Trace和决定审计。
     * 已启动后的失败或取消保留FAILED，不自动开放可能已经执行的工具。
     * INPUT_REQUIRED 继续通过stream发送实际补充内容。
     * </p>
     *
     * @param request
     * @return
     */
    public Flux<String> replay(@Valid ConversationReplayRequest request) {
        return Flux.defer(() -> {
            // 固定此次提交快照，避免调用方修改集合或参数后改变已审计的决定。
            ConversationReplayRequest submission = JSON.parseObject(JSON.toJSONString(request), ConversationReplayRequest.class);
            ApprovalReplay replay = prepareReplay(submission);
            persistReplay(replay, submission);
            try {
                ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run = agentExecutePipeline.run(
                        replay.request(), result -> Mono.fromRunnable(() -> handleChatResult(replay.aggregate(), result, replay)));
                return streamRun(replay.aggregate(), run, replay);
            } catch (RuntimeException error) {
                // 创建/登记前尚无执行者，才允许释放票证；Run启动后绝不按HTTP失败重放。
                updateTraceStateQuietly(replay.aggregate().gerRunId(), TaskState.FAILED);
                releaseUnstartedApproval(replay);
                return Flux.error(error);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    /** 可信批次、完整决定及原生参数校验均先于票证占用，不在这里执行工具。 */
    private ApprovalReplay prepareReplay(ConversationReplayRequest request) {
        if (request == null || StringUtils.isAnyBlank(request.getUserId(), request.getConversationId(),
                request.getPauseId(), request.getRequestId()) || request.getPauseVersion() == null
                || request.getPauseVersion() <= 0 || request.getRequestId().length() > 128
                || request.getDecisions() == null || request.getDecisions().isEmpty()) {
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_DECISIONS_INVALID);
        }
        log.info("[REPLAY]收到整批审批, userId:{}, conversationId:{}, pauseId:{}, requestId:{}, decisionCount:{}",
                request.getUserId(), request.getConversationId(), request.getPauseId(), request.getRequestId(), request.getDecisions().size());
        ChatConversation conversation = chatConversationDomainService.findByUseIdAndConversationId(
                request.getUserId(), request.getConversationId());
        if (conversation == null) {
            throw new BusinessRuntimeException(TripAgentResultCode.CONVERSATION_NOT_EXIST);
        }
        List<ChatMessageHitl> tickets = chatMessageHitlDomainService.findBatch(conversation.getConversationId(), request.getPauseId());
        validateReplayTickets(tickets, request);
        ChatMessageHitl first = tickets.getFirst();
        Map<String, ConversationReplayRequest.Decision> decisions = new LinkedHashMap<>();
        for (ConversationReplayRequest.Decision decision : request.getDecisions()) {
            if (decision == null || StringUtils.isBlank(decision.getMessageId()) || decision.getAction() == null
                    || decisions.putIfAbsent(decision.getMessageId(), decision) != null) {
                throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_DECISIONS_INVALID);
            }
            if (decision.getAction() == AgentApprovalAction.EDIT) {
                if (decision.getParams() == null || decision.getParams().isEmpty()) {
                    throw new BusinessRuntimeException(TripAgentResultCode.HITL_EDIT_PARAMS_REQUIRED);
                }
            } else if (decision.getParams() != null && !decision.getParams().isEmpty()) {
                throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_DECISIONS_INVALID);
            }
        }
        List<String> messageIds = tickets.stream().map(ChatMessageHitl::getMessageId).toList();
        if (!decisions.keySet().equals(new HashSet<>(messageIds))) {
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_DECISIONS_INVALID);
        }
        List<ChatMessage> approvals = chatMessageDomainService.listByIds(messageIds);
        Assert.isTrue(approvals.size() == tickets.size() && approvals.stream().allMatch(message ->
                        message.getRole() == ChatRole.AGENT_HITL && conversation.getConversationId().equals(message.getConversationId())),
                () -> BusinessRuntimeException.of(TripAgentResultCode.HITL_MESSAGE_NOT_EXIST));

        // 客户端只决定动作，hitlId、原Run和所有恢复坐标均来自已保存的票证。
        HitlRequestInfo hitl = HitlRequestInfo.builder().humanInTheLoopKind(HumanInTheLoopKind.APPROVAL)
                .pauseId(first.getPauseId()).pauseVersion(first.getPauseVersion()).originRunId(first.getOriginRunId())
                .requestId(request.getRequestId()).decisions(tickets.stream().map(ticket -> {
                    ConversationReplayRequest.Decision decision = decisions.get(ticket.getMessageId());
                    return HitlRequestInfo.builder().hitlId(ticket.getHitlId()).decision(decision.getAction())
                            .params(decision.getParams()).build();
                }).toList()).build();
        ChatRequestTrace trace = ChatRequestTrace.create(conversation.getConversationId());
        AgentRequest agentRequest = AgentRequest.builder().userId(conversation.getUserId())
                .conversationId(conversation.getConversationId()).runId(trace.getRunId()).hitlRequestInfo(hitl).build();
        try {
            agentExecutePipeline.validateApprovalResume(agentRequest);
        } catch (BizException error) {
            log.warn("[REPLAY]原生批次预检查未通过, conversationId:{}, pauseId:{}", conversation.getConversationId(), first.getPauseId());
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_VALIDATION_FAILED);
        }
        ChatMessage audit = ChatMessage.createUser(conversation.getConversationId(), trace.getRunId(), ChatRole.USER_RESUME,
                ChatMessageRequest.builder().messageType(ChatMessageContentType.TEXT).content("已提交本批全部审批决定").build());
        audit.setExtra(JSON.toJSONString(Map.of("requestId", request.getRequestId(), "pauseId", first.getPauseId(),
                "pauseVersion", first.getPauseVersion(), "decisions", request.getDecisions())));
        return new ApprovalReplay(new ChatMessageAggregate(conversation, trace, List.of(audit)), agentRequest, messageIds);
    }

    /** 快照读取与事务内重读均使用此校验；条件更新负责覆盖最后的并发占用窗口。 */
    private void validateReplayTickets(List<ChatMessageHitl> tickets, ConversationReplayRequest request) {
        if (tickets.isEmpty()) {
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_DECISIONS_INVALID);
        }
        if (tickets.stream().anyMatch(ticket -> ticket.getStatus() == ChatMessageHitlStatus.PREPARED)) {
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_NOT_READY);
        }
        if (tickets.stream().anyMatch(ticket -> ticket.getStatus() == ChatMessageHitlStatus.FAILED)) {
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_RESUME_FAILED);
        }
        if (chatMessageHitlDomainService.hasResumeRequest(request.getConversationId(), request.getRequestId())) {
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_REQUEST_ID_ALREADY_USED);
        }
        if (tickets.stream().anyMatch(ticket -> ticket.getStatus() != ChatMessageHitlStatus.PENDING)) {
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_MESSAGE_ALREADY_USED);
        }
        if (tickets.stream().anyMatch(ticket -> !Objects.equals(ticket.getPauseVersion(), request.getPauseVersion()))) {
            throw new BusinessRuntimeException(TripAgentResultCode.HITL_BATCH_VERSION_EXPIRED);
        }
        ChatMessageHitl first = tickets.getFirst();
        Assert.isTrue(tickets.stream().allMatch(ticket -> StringUtils.isNoneBlank(ticket.getHitlId(), ticket.getOriginRunId(),
                        ticket.getCheckpointId(), ticket.getPipelineRunId(), ticket.getResumeRef())
                        && Objects.equals(first.getOriginRunId(), ticket.getOriginRunId())
                        && Objects.equals(first.getPipelineRunId(), ticket.getPipelineRunId())),
                () -> BusinessRuntimeException.of(TripAgentResultCode.HITL_LEGACY_CREDENTIAL));
    }

    /** 整批占用、一个恢复Trace和一个完整决定审计原子保存；影响行数不足全部回滚。 */
    private void persistReplay(ApprovalReplay replay, ConversationReplayRequest submission) {
        transactionTemplate.executeWithoutResult(status -> {
            List<ChatMessageHitl> current = chatMessageHitlDomainService.findBatch(submission.getConversationId(), submission.getPauseId());
            validateReplayTickets(current, submission);
            Assert.isTrue(current.stream().map(ChatMessageHitl::getMessageId).toList().equals(replay.messageIds()),
                    () -> BusinessRuntimeException.of(TripAgentResultCode.HITL_BATCH_DECISIONS_INVALID));
            Assert.isTrue(chatMessageHitlDomainService.claimBatch(submission.getConversationId(), submission.getPauseId(),
                            submission.getPauseVersion(), replay.messageIds(), submission.getRequestId(), replay.aggregate().gerRunId())
                            == replay.messageIds().size(),
                    () -> BusinessRuntimeException.of(TripAgentResultCode.HITL_MESSAGE_ALREADY_USED));
            Assert.isTrue(chatRequestTraceDomainService.save(replay.aggregate().getTrace()),
                    () -> SystemIntervalException.of("审批恢复Trace持久化失败"));
            Assert.isTrue(chatMessageDomainService.saveBatch(replay.aggregate().getMessages()),
                    () -> SystemIntervalException.of("审批完整决定审计持久化失败"));
        });
    }

    /** 固定本次恢复的持有者及消息集合，只在该请求的Pipeline闭包内使用。 */
    private record ApprovalReplay(ChatMessageAggregate aggregate, AgentRequest request, List<String> messageIds) {
    }

    /**
     * 删除会话
     * @param conversationId
     * @param userId
     * @return
     */
    public R<Void> delete(String conversationId, String userId) {
        log.info("[CHAT]删除会话, conversationId:{}, userId:{}", conversationId, userId);
        // 查询会话是否存在
        ChatConversation conversation = chatConversationDomainService.findByUseIdAndConversationId(userId, conversationId);
        if (conversation == null) {
            return R.failed(TripAgentResultCode.CONVERSATION_NOT_EXIST);
        }
        // 删除会话以及聊天记录
        Boolean execute = transactionTemplate.execute(status -> {
            try {
                Assert.isTrue(chatConversationDomainService.removeById(conversation), () -> SystemIntervalException.of("删除会话失败"));
                Assert.isTrue(chatRequestTraceDomainService.removeByConversationId(conversationId), () -> SystemIntervalException.of("删除Trace失败"));
                Assert.isTrue(chatMessageDomainService.removeByConversationId(conversationId), () -> SystemIntervalException.of("删除聊天记录失败"));
                return true;
            } catch (Exception e) {
                status.setRollbackOnly();
                log.error(e.getMessage(), e);
                return false;
            }
        });

        return Boolean.TRUE.equals(execute) ? R.success() : R.failed(ResultCode.SYSTEM_BUSY);
    }

    private void releaseUnstartedApproval(ApprovalReplay replay) {
        if (replay != null) {
            try {
                finishReplay(replay, ChatMessageHitlStatus.PENDING, true);
            } catch (Exception error) {
                log.error("[REPLAY]释放未启动审批批次失败, runId:{}", replay.aggregate().gerRunId(), error);
            }
        }
    }

    /** 异常及取消可能发生在结果处理器之前；只关闭仍由本Run持有的票证。 */
    private void failReplayQuietly(ApprovalReplay replay) {
        if (replay != null) {
            try {
                finishReplay(replay, ChatMessageHitlStatus.FAILED, false);
            } catch (Exception error) {
                log.error("[REPLAY]保存恢复失败状态异常, runId:{}", replay.aggregate().gerRunId(), error);
            }
        }
    }

    private void finishReplay(ApprovalReplay replay, ChatMessageHitlStatus status, boolean requireAll) {
        HitlRequestInfo hitl = replay.request().getHitlRequestInfo();
        transactionTemplate.executeWithoutResult(transaction -> {
            int changed = chatMessageHitlDomainService.finishResume(replay.aggregate().getConversationId(), hitl.getPauseId(),
                    hitl.getRequestId(), replay.aggregate().gerRunId(), status);
            Assert.isTrue(changed == replay.messageIds().size() || !requireAll && changed == 0,
                    () -> SystemIntervalException.of("审批批次状态更新不完整"));
        });
    }

    private void handleChatResult(ChatMessageAggregate aggregate, AgentExecutePipeline.PipelineResult result, ApprovalReplay replay) {
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
                ? createApprovalDetails(masterResult, chatMessages, result.pipelineRunId())
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
                if (replay != null) {
                    // 子节点再次ASK时，旧决定已消费，新票证另成一批；失败不重新开放旧工具。
                    ChatMessageHitlStatus resumeStatus = switch (state) {
                        case COMPLETED, WAITING_APPROVAL, APPROVAL_REJECTED -> ChatMessageHitlStatus.CONSUMED;
                        default -> ChatMessageHitlStatus.FAILED;
                    };
                    finishReplay(replay, resumeStatus, true);
                }
            } catch (Exception e) {
                status.setRollbackOnly();
                throw e;
            }
        });
        log.info("[CHAT]保存Agent结果完成, runId:{}, state:{}, messageCount:{}", aggregate.gerRunId(), state, chatMessages.size());
    }

    private List<ChatMessageHitl> createApprovalDetails(AgentRunResult result, List<ChatMessage> messages,
                                                       String pipelineRunId) {
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
        HumanInTheLoopInfo first = approvals.getFirst();
        HashSet<String> ids = new HashSet<>();
        for (HumanInTheLoopInfo approval : approvals) {
            Assert.isTrue(StringUtils.isNoneBlank(approval.getId(), approval.getOriginRunId(),
                            approval.getCheckpointId(), approval.getPauseId(), approval.getResumeRef())
                            && approval.getPauseVersion() != null && ids.add(approval.getId()),
                    () -> SystemIntervalException.of("Agent审批凭证缺少恢复标识或存在重复项"));
            Assert.isTrue(Objects.equals(first.getPauseId(), approval.getPauseId())
                            && Objects.equals(first.getPauseVersion(), approval.getPauseVersion())
                            && Objects.equals(result.getRunId(), approval.getOriginRunId()),
                    () -> SystemIntervalException.of("同次收口的审批必须属于同一批次和原Run"));
        }
        return IntStream.range(0, approvals.size())
                .mapToObj(index -> ChatMessageHitl.create(
                        approvalMessages.get(index).getMessageId(), approvals.get(index),
                        result.getConversationId(), pipelineRunId))
                .toList();
    }

    /**
     * 在Pipeline的权威completion后交接执行权，再将完整审批批次开放给客户端。
     * 存储或交接失败时保留PREPARED，历史接口也不能提前展示这些卡片。
     */
    private Mono<List<MessageInfo>> finalizeRunResult(ChatMessageAggregate aggregate,
                                                     AgentExecutePipeline.PipelineResult result) {
        return Mono.fromCallable(() -> {
            String conversationId = aggregate.getConversationId();
            String pipelineRunId = result.pipelineRunId();
            // 释放失败必须中止正式发布；doFinally仍会尝试幂等清理，但不能提前开放审批。
            reactiveRunManager.release(aggregate.getTrace().getTaskId(), pipelineRunId);
            if (!chatConversationDomainService.clearActiveRunIdIfMatch(conversationId, pipelineRunId)) {
                ChatConversation current = chatConversationDomainService.findByUseIdAndConversationId(
                        aggregate.getConversation().getUserId(), conversationId);
                Assert.isTrue(current != null && StringUtils.isBlank(current.getActiveRunId()),
                        () -> SystemIntervalException.of("会话执行权尚未交接，不能发布本批审批"));
            }
            AgentRunResult master = result.masterAgentResult();
            if (master.getState() != AgentRunState.WAITING_APPROVAL) {
                return List.<MessageInfo>of();
            }
            HumanInTheLoopInfo first = master.getHumanInTheLoopInfos().getFirst();
            return transactionTemplate.execute(status -> {
                List<ChatMessageHitl> tickets = chatMessageHitlDomainService.findBatch(
                        conversationId, first.getPauseId(), first.getPauseVersion(), pipelineRunId);
                Assert.isTrue(tickets.size() == master.getHumanInTheLoopInfos().size()
                                && tickets.stream().allMatch(ticket -> ticket.getStatus() == ChatMessageHitlStatus.PREPARED),
                        () -> SystemIntervalException.of("准备中的审批批次不完整，不能开放部分审批"));
                Assert.isTrue(chatMessageHitlDomainService.markBatchPending(conversationId, first.getPauseId(),
                                first.getPauseVersion(), pipelineRunId) == tickets.size(),
                        () -> SystemIntervalException.of("审批批次就绪状态更新冲突"));
                tickets.forEach(ticket -> ticket.setStatus(ChatMessageHitlStatus.PENDING));
                Map<String, ChatMessage> messages = chatMessageDomainService.listByIds(
                                tickets.stream().map(ChatMessageHitl::getMessageId).toList()).stream()
                        .collect(Collectors.toMap(ChatMessage::getMessageId, Function.identity()));
                Assert.isTrue(messages.size() == tickets.size(),
                        () -> SystemIntervalException.of("审批消息缺失，不能发布恢复凭证"));
                return tickets.stream().map(ticket -> approvalMessageInfo(messages.get(ticket.getMessageId()), ticket))
                        .toList();
            });
        }).subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> updateTraceStateQuietly(aggregate.gerRunId(), TaskState.FAILED));
    }

    /** 正式事件只使用已持久化消息及凭证；客户端按messageId展示并提交整批决定。 */
    private MessageInfo approvalMessageInfo(ChatMessage message, ChatMessageHitl ticket) {
        return MessageInfo.builder().id(message.getMessageId()).role(message.getRole().getCode())
                .content(message.getContent()).agentName(message.getAgentName()).thinking(message.getThinking())
                .created(message.getCreated() == null ? null : message.getCreated().getTime())
                .messageContentType(message.getType().name()).hitlId(ticket.getHitlId())
                .pauseId(ticket.getPauseId()).pauseVersion(ticket.getPauseVersion()).resumeRef(ticket.getResumeRef())
                .hitlStatus(ticket.getStatus().getCode())
                .hitlData(StringUtils.isBlank(ticket.getHitlData()) ? null : JSON.parseObject(ticket.getHitlData())).build();
    }

    private void requireNoPendingApproval(String conversationId) {
        if (chatMessageHitlDomainService.hasUnresolvedApproval(conversationId)) {
            throw new BusinessRuntimeException(TripAgentResultCode.CONVERSATION_WAITING_APPROVAL);
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
            requireNoPendingApproval(conversation.getConversationId());
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
