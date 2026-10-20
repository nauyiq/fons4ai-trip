package com.fons.cloud.ai.trip.application.conversation;

import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopInfo;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutePipeline;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.vo.MessageInfo;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.ai.trip.infrastructure.notification.ItineraryPlanPageNotificationRegistry;
import com.fons.cloud.reactor.api.ReactiveTaskRun;
import com.fons.cloud.reactor.model.ReactiveTaskState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * 将一次管道Run的过程事件和权威收口结果适配为应用层聊天流。
 *
 * <p>流开始时先发送包含conversationId的{@code conversation_ready}事件，
 * 框架内部HITL过程通知不直接透传；事件通道结束后读取同一个Run的completion，在终态前按需发送
 * {@code itinerary_page_ready}，最后追加{@code run_ended}。两次订阅的是同一个Run的不同通道，不会再次执行管道。
 * 结果处理器先于completion运行，因此该事件也表示本轮后置处理已经返回。</p>
 *
 * <p>终态事件的data包含conversationId、pipelineRunId、可用时的agentRunId、state和nextAction。
 * nextAction取值为none、input_required或approval；INPUT_REQUIRED对应的state仍为completed，
 * 信息补充放在humanInTheLoopInfos中，不表示审批暂停；审批暂停使用已持久化的
 * approvalMessages，并携带同批pauseId、pauseVersion及approvalReady；取消提示使用
 * terminalMessages，与历史消息复用同一条持久化记录。</p>
 *
 * <p>state描述本轮Agent执行状态，不代表所有业务目标均已成功、审核通过或预订完成。
 * 任务清单是过程记录，不能用清单全部completed替代候选、方案及审核的实际业务结果。</p>
 * @author hoqngqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatStreamOutputAdapter {

    private final ItineraryPlanPageNotificationRegistry pageNotificationRegistry;

    public Flux<String> adapt(ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run,
                              CandidateOwner owner) {
        return adapt(run, owner, run.termination().thenReturn(List.of()));
    }

    /**
     * 结果与实际退出分别确认；finalizedMessages 完成执行权交接后，返回持久化的审批或取消消息。
     * 正常、失败和取消路径共用这一个收尾入口；交接失败时不能宣告空闲或开放审批。
     */
    public Flux<String> adapt(ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run,
                              CandidateOwner owner,
                              Mono<List<MessageInfo>> finalizedMessages) {
        String conversationId = owner.conversationId();
        return Flux.defer(() -> Flux.just(conversationReadyEvent(conversationId, run.runId()))
                .concatWith(run.events()
                        .filter(event -> !isInternalHitl(event))
                        // 过程事件不是权威终态；即使过程通道失败，也要读取completion。
                        .onErrorResume(error -> {
                            log.warn("聊天过程事件通道异常，等待管道最终结果，pipelineRunId:{}", run.runId(), error);
                            return Flux.empty();
                        }))
                .concatWith(run.completion().materialize()
                        .flatMap(outcome -> finalizedMessages.flatMap(messages -> Mono.fromCallable(() -> {
                            if (!outcome.hasValue()) {
                                log.warn("聊天管道未形成最终结果，pipelineRunId:{}", run.runId(), outcome.getThrowable());
                                return unresolvedEvent(run.runId(), run.state(), conversationId, messages);
                            }
                            if (run.state() != ReactiveTaskState.COMPLETED) {
                                throw SystemIntervalException.of("Pipeline尚未完成，不能发布正式审批");
                            }
                            return completedEvent(outcome.get(), conversationId, messages);
                        }).onErrorResume(error -> {
                            log.error("聊天结果发布失败，pipelineRunId:{}", run.runId(), error);
                            return Mono.just(unresolvedEvent(run.runId(), ReactiveTaskState.FAILED, conversationId, List.of()));
                        })))
                        .flatMapMany(terminalEvent -> pageReadyEvent(owner)
                                .concatWith(Mono.just(terminalEvent))))
                .doFinally(signal -> pageNotificationRegistry.discard(owner)));
    }

    /** 过程通知不是业务审批凭证；正式卡片仅从持久化后的run_ended获得。 */
    private static boolean isInternalHitl(String event) {
        try {
            return "hitl".equalsIgnoreCase(JSONObject.parseObject(event).getString("type"));
        } catch (RuntimeException error) {
            // 保留原有非JSON过程消息的兼容行为，不解析模型正文来识别审批。
            return false;
        }
    }

    private Mono<String> pageReadyEvent(CandidateOwner owner) {
        return Mono.defer(() -> Mono.justOrEmpty(pageNotificationRegistry.consume(owner))
                .map(page -> {
                    JSONObject data = new JSONObject();
                    data.put("conversationId", owner.conversationId());
                    data.put("planId", page.planId());
                    data.put("reviewId", page.reviewId());
                    data.put("recommendedProposalId", page.recommendedProposalId());
                    return event("itinerary_page_ready", data);
                }));
    }

    private static String conversationReadyEvent(String conversationId, String pipelineRunId) {
        JSONObject data = new JSONObject();
        data.put("conversationId", conversationId);
        data.put("pipelineRunId", pipelineRunId);
        return event("conversation_ready", data);
    }

    private String completedEvent(AgentExecutePipeline.PipelineResult result, String conversationId,
                                  List<MessageInfo> finalizedMessages) {
        AgentRunResult master = result.masterAgentResult();
        List<HumanInTheLoopInfo> interactions = master.getHumanInTheLoopInfos();
        JSONObject data = new JSONObject();
        data.put("conversationId", conversationId);
        data.put("pipelineRunId", result.pipelineRunId());
        data.put("agentRunId", master.getRunId());
        data.put("state", master.getState().name().toLowerCase(Locale.ROOT));
        data.put("nextAction", nextAction(master.getState(), interactions));
        if (master.getState() == AgentRunState.WAITING_APPROVAL) {
            if (finalizedMessages.size() != interactions.size() || finalizedMessages.isEmpty()
                    || finalizedMessages.stream().anyMatch(message -> !ChatMessageHitlStatus.PENDING.getCode()
                    .equals(message.getHitlStatus()))) {
                throw SystemIntervalException.of("未取得完整且就绪的持久化审批消息");
            }
            MessageInfo first = finalizedMessages.getFirst();
            data.put("pauseId", first.getPauseId());
            data.put("pauseVersion", first.getPauseVersion());
            data.put("approvalReady", true);
            data.put("approvalMessages", finalizedMessages);
        } else if (!interactions.isEmpty()) {
            data.put("humanInTheLoopInfos", interactions);
        }
        if (master.getState() == AgentRunState.CANCELLED) {
            data.put("terminalMessages", finalizedMessages);
        }
        if (master.getState() != AgentRunState.COMPLETED
                && master.getState() != AgentRunState.WAITING_APPROVAL) {
            if (master.getErrorCode() != null) {
                data.put("errorCode", master.getErrorCode());
            }
            if (master.getErrorMessage() != null) {
                data.put("errorMessage", master.getErrorMessage());
            }
        }
        return event("run_ended", data);
    }

    private static String nextAction(AgentRunState state, List<HumanInTheLoopInfo> interactions) {
        if (state == AgentRunState.WAITING_APPROVAL) {
            return "approval";
        }
        if (state == AgentRunState.COMPLETED && interactions.stream()
                .anyMatch(info -> info.getKind() == HumanInTheLoopKind.INPUT_REQUIRED)) {
            return "input_required";
        }
        return "none";
    }

    private static String unresolvedEvent(String pipelineRunId, ReactiveTaskState taskState,
                                          String conversationId, List<MessageInfo> terminalMessages) {
        JSONObject data = new JSONObject();
        data.put("conversationId", conversationId);
        data.put("pipelineRunId", pipelineRunId);
        boolean cancelled = taskState == ReactiveTaskState.CANCELLED;
        data.put("state", cancelled ? "cancelled" : "failed");
        data.put("nextAction", "none");
        if (!terminalMessages.isEmpty()) {
            data.put("terminalMessages", terminalMessages);
        }
        if (!cancelled) {
            data.put("errorMessage", "聊天请求执行失败");
        }
        return event("run_ended", data);
    }

    private static String event(String type, JSONObject data) {
        JSONObject event = new JSONObject();
        event.put("type", type);
        event.put("data", data);
        return event.toJSONString();
    }
}
