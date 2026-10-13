package com.fons.cloud.ai.trip.application.conversation;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopInfo;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.agent.model.response.AgentCompleteInfo;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutePipeline;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanPageReady;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.infrastructure.notification.ItineraryPlanPageNotificationRegistry;
import com.fons.cloud.reactor.api.ReactiveTaskRun;
import com.fons.cloud.reactor.model.ReactiveTaskState;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ChatStreamOutputAdapterTest {

    private final CandidateOwner owner = new CandidateOwner("user-1", "conversation-1");
    private final ItineraryPlanPageNotificationRegistry notifications = new ItineraryPlanPageNotificationRegistry();
    private final ChatStreamOutputAdapter adapter = new ChatStreamOutputAdapter(notifications);

    @Test
    void inputRequiredKeepsOriginalEventAndAddsCompletedTerminalEvent() {
        var interaction = HumanInTheLoopInfo.builder()
                .id("interaction-1")
                .originRunId("agent-1")
                .sourceAgent("child-agent")
                .kind(HumanInTheLoopKind.INPUT_REQUIRED)
                .question("请补充出发城市")
                .build();
        var master = result(AgentRunState.COMPLETED, "请补充出发城市", List.of(interaction));
        var pipeline = new AgentExecutePipeline.PipelineResult("pipeline-1", null, null, master);
        StubRun run = new StubRun(Flux.just("{\"type\":\"text\",\"content\":\"请补充出发城市\"}"),
                Mono.just(pipeline));

        List<String> events = adapter.adapt(run, owner).collectList().block();

        assertNotNull(events);
        assertEquals(3, events.size());
        JSONObject ready = JSON.parseObject(events.getFirst());
        assertEquals("conversation_ready", ready.getString("type"));
        assertEquals("conversation-1", ready.getJSONObject("data").getString("conversationId"));
        assertEquals("text", JSON.parseObject(events.get(1)).getString("type"));
        JSONObject ended = JSON.parseObject(events.getLast());
        assertEquals("run_ended", ended.getString("type"));
        assertEquals("conversation-1", ended.getJSONObject("data").getString("conversationId"));
        assertEquals("completed", ended.getJSONObject("data").getString("state"));
        assertEquals("input_required", ended.getJSONObject("data").getString("nextAction"));
        assertEquals("INPUT_REQUIRED", ended.getJSONObject("data")
                .getJSONArray("humanInTheLoopInfos").getJSONObject(0).getString("kind"));
        assertEquals(1, run.eventsCalls.get());
        assertEquals(1, run.completionCalls.get());

        ChatMessage message = ChatMessage.createAgent("master", master).getFirst();
        assertEquals(ChatRole.AGENT, message.getRole());
        assertEquals("请补充出发城市", message.getContent());
    }

    @Test
    void approvalStaysApprovalAndDoesNotBecomeNormalAnswer() {
        var interaction = HumanInTheLoopInfo.builder()
                .id("approval-1")
                .sourceAgent("master")
                .kind(HumanInTheLoopKind.APPROVAL)
                .build();
        var master = result(AgentRunState.WAITING_APPROVAL, "not-the-approval-payload", List.of(interaction));
        var pipeline = new AgentExecutePipeline.PipelineResult("pipeline-2", null, null, master);
        StubRun run = new StubRun(Flux.empty(), Mono.just(pipeline));

        JSONObject ended = JSON.parseObject(adapter.adapt(run, owner).blockLast());
        assertEquals("conversation-1", ended.getJSONObject("data").getString("conversationId"));
        assertEquals("waiting_approval", ended.getJSONObject("data").getString("state"));
        assertEquals("approval", ended.getJSONObject("data").getString("nextAction"));

        ChatMessage message = ChatMessage.createAgent("master", master).getFirst();
        assertEquals(ChatRole.AGENT_HITL, message.getRole());
        assertEquals("Agent 即将执行以下操作，请确认", message.getContent());
    }

    @Test
    void pageNotificationContainsOnlyLookupIdsBeforeRunEnds() {
        var pipeline = new AgentExecutePipeline.PipelineResult("pipeline-3", null, null,
                result(AgentRunState.COMPLETED, "方案已生成", List.of()));
        StubRun run = new StubRun(Flux.defer(() -> {
            notifications.publish(owner, new ItineraryPlanPageReady("PLAN-1", "REVIEW-1", "PROPOSAL-1"));
            return Flux.empty();
        }), Mono.just(pipeline));

        List<String> events = adapter.adapt(run, owner).collectList().block();

        assertNotNull(events);
        assertEquals(3, events.size());
        JSONObject page = JSON.parseObject(events.get(1));
        assertEquals("itinerary_page_ready", page.getString("type"));
        assertEquals("conversation-1", page.getJSONObject("data").getString("conversationId"));
        assertEquals("PLAN-1", page.getJSONObject("data").getString("planId"));
        assertEquals("REVIEW-1", page.getJSONObject("data").getString("reviewId"));
        assertFalse(page.toJSONString().contains("objectKey"));
        assertFalse(page.toJSONString().contains("html"));
        assertEquals("run_ended", JSON.parseObject(events.get(2)).getString("type"));
        assertNull(notifications.consume(owner));
    }

    @Test
    void cancelledStreamDiscardsOnlyItsOwnPendingPage() {
        CandidateOwner otherOwner = new CandidateOwner("user-2", "conversation-2");
        notifications.publish(owner, new ItineraryPlanPageReady("PLAN-1", "REVIEW-1", "PROPOSAL-1"));
        notifications.publish(otherOwner, new ItineraryPlanPageReady("PLAN-2", "REVIEW-2", "PROPOSAL-2"));
        StubRun run = new StubRun(Flux.never(), Mono.never());

        adapter.adapt(run, owner).take(1).blockLast();

        assertNull(notifications.consume(owner));
        assertEquals("PLAN-2", notifications.consume(otherOwner).planId());
    }

    private static class StubRun implements ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> {

        private final Flux<String> eventFlux;
        private final Mono<AgentExecutePipeline.PipelineResult> resultMono;
        private final AtomicInteger eventsCalls = new AtomicInteger();
        private final AtomicInteger completionCalls = new AtomicInteger();

        private StubRun(Flux<String> eventFlux, Mono<AgentExecutePipeline.PipelineResult> resultMono) {
            this.eventFlux = eventFlux;
            this.resultMono = resultMono;
        }

        @Override
        public String runId() {
            return "pipeline-test";
        }

        @Override
        public ReactiveTaskState state() {
            return ReactiveTaskState.COMPLETED;
        }

        @Override
        public Flux<String> events() {
            eventsCalls.incrementAndGet();
            return eventFlux;
        }

        @Override
        public Mono<AgentExecutePipeline.PipelineResult> completion() {
            completionCalls.incrementAndGet();
            return resultMono;
        }

        @Override
        public boolean cancel() {
            return true;
        }
    }

    private static AgentRunResult result(AgentRunState state, String answer,
                                         List<HumanInTheLoopInfo> interactions) {
        return AgentRunResult.builder()
                .runId("agent-1")
                .conversationId("conversation-1")
                .state(state)
                .completeInfo(AgentCompleteInfo.builder().finalAnswer(answer).build())
                .humanInTheLoopInfos(interactions)
                .build();
    }
}
