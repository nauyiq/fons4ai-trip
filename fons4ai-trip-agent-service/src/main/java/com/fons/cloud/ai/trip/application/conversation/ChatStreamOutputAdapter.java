package com.fons.cloud.ai.trip.application.conversation;

import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopInfo;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutePipeline;
import com.fons.cloud.reactor.api.ReactiveTaskRun;
import com.fons.cloud.reactor.model.ReactiveTaskState;
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
 * 原有Agent事件保持原样；事件通道结束后读取同一个Run的completion，追加一条
 * {@code run_ended}事件。两次订阅的是同一个Run的不同通道，不会再次执行管道。
 * 结果处理器先于completion运行，因此该事件也表示本轮后置处理已经返回。</p>
 *
 * <p>终态事件的data包含conversationId、pipelineRunId、可用时的agentRunId、state和nextAction。
 * nextAction取值为none、input_required或approval；INPUT_REQUIRED对应的state仍为completed，
 * 结构化交互信息放在humanInTheLoopInfos中，不表示审批暂停。</p>
 */
@Slf4j
@Component
public class ChatStreamOutputAdapter {

    public Flux<String> adapt(ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run,
                              String conversationId) {
        return Flux.defer(() -> Flux.just(conversationReadyEvent(conversationId, run.runId()))
                .concatWith(run.events()
                        // 过程事件不是权威终态；即使过程通道失败，也要读取completion。
                        .onErrorResume(error -> {
                            log.warn("聊天过程事件通道异常，等待管道最终结果，pipelineRunId:{}", run.runId(), error);
                            return Flux.empty();
                        }))
                .concatWith(run.completion()
                        .map(result -> completedEvent(result, conversationId))
                        .onErrorResume(error -> {
                            log.warn("聊天管道未形成最终结果，pipelineRunId:{}", run.runId(), error);
                            return Mono.just(unresolvedEvent(run.runId(), run.state(), conversationId));
                        })));
    }

    private static String conversationReadyEvent(String conversationId, String pipelineRunId) {
        JSONObject data = new JSONObject();
        data.put("conversationId", conversationId);
        data.put("pipelineRunId", pipelineRunId);
        return event("conversation_ready", data);
    }

    private String completedEvent(AgentExecutePipeline.PipelineResult result, String conversationId) {
        AgentRunResult master = result.masterAgentResult();
        List<HumanInTheLoopInfo> interactions = master.getHumanInTheLoopInfos();
        JSONObject data = new JSONObject();
        data.put("conversationId", conversationId);
        data.put("pipelineRunId", result.pipelineRunId());
        data.put("agentRunId", master.getRunId());
        data.put("state", master.getState().name().toLowerCase(Locale.ROOT));
        data.put("nextAction", nextAction(master.getState(), interactions));
        if (!interactions.isEmpty()) {
            data.put("humanInTheLoopInfos", interactions);
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
                                          String conversationId) {
        JSONObject data = new JSONObject();
        data.put("conversationId", conversationId);
        data.put("pipelineRunId", pipelineRunId);
        boolean cancelled = taskState == ReactiveTaskState.CANCELLED;
        data.put("state", cancelled ? "cancelled" : "failed");
        data.put("nextAction", "none");
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
