package com.fons.cloud.ai.trip.application.conversation;

import com.fons.cloud.ai.agent.infrastructure.session.ActiveAgentSessionStore;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopInfo;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.agent.model.request.AgentInputContent;
import com.fons.cloud.ai.agent.model.request.AgentInputContentType;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.request.AgentApprovalAction;
import com.fons.cloud.ai.agent.model.response.AgentCompleteInfo;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutePipeline;
import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.constants.TaskState;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.ai.trip.common.request.ConversationInterruptRequest;
import com.fons.cloud.ai.trip.common.request.ConversationReplayRequest;
import com.fons.cloud.ai.trip.common.request.ConversationStreamRequest;
import com.fons.cloud.ai.trip.domain.entity.ChatConversation;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageHitl;
import com.fons.cloud.ai.trip.domain.entity.ChatRequestTrace;
import com.fons.cloud.ai.trip.domain.service.ChatConversationDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatMessageHitlDomainService;
import com.fons.cloud.ai.trip.domain.service.ChatRequestTraceDomainService;
import com.fons.cloud.ai.trip.infrastructure.converter.ChatMessageConverter;
import com.fons.cloud.reactor.api.ReactiveTaskRun;
import com.fons.cloud.reactor.api.ReactiveResultHandler;
import com.fons.cloud.reactor.core.ReactiveRunManager;
import com.fons.cloud.reactor.model.ReactiveTaskState;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConversationApplicationServiceTest {

    @Test
    @SuppressWarnings("unchecked")
    void newConversationUsesPersistedIdForAgentRequest() {
        ChatMessageConverter converter = mock(ChatMessageConverter.class);
        ActiveAgentSessionStore activeAgentStore = mock(ActiveAgentSessionStore.class);
        AgentExecutePipeline pipeline = mock(AgentExecutePipeline.class);
        ChatStreamOutputAdapter outputAdapter = mock(ChatStreamOutputAdapter.class);
        ReactiveRunManager runManager = mock(ReactiveRunManager.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        ChatMessageDomainService messages = mock(ChatMessageDomainService.class);
        ChatMessageHitlDomainService hitls = mock(ChatMessageHitlDomainService.class);
        ChatConversationDomainService conversations = mock(ChatConversationDomainService.class);
        ChatRequestTraceDomainService traces = mock(ChatRequestTraceDomainService.class);
        ConversationApplicationService service = new ConversationApplicationService(
                converter, activeAgentStore, pipeline, outputAdapter, runManager,
                transactions, messages, hitls, conversations, traces);

        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(conversations.save(any())).thenReturn(true);
        when(traces.save(any())).thenReturn(true);
        when(messages.saveBatch(any())).thenReturn(true);
        when(converter.convertAgentInputContent(any())).thenReturn(AgentInputContent.builder()
                .type(AgentInputContentType.TEXT).text("请安排出差").build());
        when(pipeline.run(any(), any())).thenReturn(mock(ReactiveTaskRun.class));

        ConversationStreamRequest request = ConversationStreamRequest.builder()
                .userId("user-1")
                .messages(List.of(ChatMessageRequest.builder()
                        .messageType(ChatMessageContentType.TEXT)
                        .content("请安排出差")
                        .build()))
                .build();
        service.stream(request);

        var persistedConversation = org.mockito.ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversations).save(persistedConversation.capture());
        var agentRequest = org.mockito.ArgumentCaptor.forClass(AgentRequest.class);
        verify(pipeline).run(agentRequest.capture(), any());
        assertNotNull(persistedConversation.getValue().getConversationId());
        assertEquals(persistedConversation.getValue().getConversationId(),
                agentRequest.getValue().getConversationId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void activeRunIdUsesPipelineIdAndIsClearedOnlyForThatRun() {
        ChatMessageConverter converter = mock(ChatMessageConverter.class);
        ActiveAgentSessionStore activeAgentStore = mock(ActiveAgentSessionStore.class);
        AgentExecutePipeline pipeline = mock(AgentExecutePipeline.class);
        ChatStreamOutputAdapter outputAdapter = mock(ChatStreamOutputAdapter.class);
        ReactiveRunManager runManager = mock(ReactiveRunManager.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        ChatMessageDomainService messages = mock(ChatMessageDomainService.class);
        ChatMessageHitlDomainService hitls = mock(ChatMessageHitlDomainService.class);
        ChatConversationDomainService conversations = mock(ChatConversationDomainService.class);
        ChatRequestTraceDomainService traces = mock(ChatRequestTraceDomainService.class);
        ConversationApplicationService service = new ConversationApplicationService(
                converter, activeAgentStore, pipeline, outputAdapter, runManager,
                transactions, messages, hitls, conversations, traces);

        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(conversations.save(any())).thenReturn(true);
        when(traces.save(any())).thenReturn(true);
        when(messages.saveBatch(any())).thenReturn(true);
        when(traces.updateState(any(), eq(TaskState.PROCESS))).thenReturn(true);
        when(conversations.updateActiveRunId(any(), eq("pipeline-1"))).thenReturn(true);
        when(converter.convertAgentInputContent(any())).thenReturn(AgentInputContent.builder()
                .type(AgentInputContentType.TEXT).text("请安排出差").build());
        ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run = mock(ReactiveTaskRun.class);
        when(run.runId()).thenReturn("pipeline-1");
        when(run.state()).thenReturn(ReactiveTaskState.COMPLETED);
        when(pipeline.run(any(), any())).thenReturn(run);
        when(runManager.register(any(), same(run))).thenReturn(ReactiveRunManager.Registration.REGISTERED);
        when(outputAdapter.adapt(same(run), any())).thenReturn(Flux.empty());

        service.stream(ConversationStreamRequest.builder()
                .userId("user-1")
                .messages(List.of(ChatMessageRequest.builder()
                        .messageType(ChatMessageContentType.TEXT)
                        .content("请安排出差")
                        .build()))
                .build()).blockLast();

        var savedTrace = org.mockito.ArgumentCaptor.forClass(ChatRequestTrace.class);
        verify(traces).save(savedTrace.capture());
        var savedConversation = org.mockito.ArgumentCaptor.forClass(ChatConversation.class);
        verify(conversations).save(savedConversation.capture());
        String conversationId = savedConversation.getValue().getConversationId();
        String traceRunId = savedTrace.getValue().getRunId();
        assertNotEquals(traceRunId, "pipeline-1");
        verify(traces).updateState(traceRunId, TaskState.PROCESS);
        verify(conversations).updateActiveRunId(conversationId, "pipeline-1");
        verify(runManager, timeout(1000)).release(savedTrace.getValue().getTaskId(), "pipeline-1");
        verify(conversations, timeout(1000)).clearActiveRunIdIfMatch(conversationId, "pipeline-1");
    }

    @Test
    void interruptCancelsPipelineRunIdAndClearsStaleValue() {
        ChatConversationDomainService conversations = mock(ChatConversationDomainService.class);
        ReactiveRunManager runManager = mock(ReactiveRunManager.class);
        ConversationApplicationService service = new ConversationApplicationService(
                mock(ChatMessageConverter.class), mock(ActiveAgentSessionStore.class),
                mock(AgentExecutePipeline.class), mock(ChatStreamOutputAdapter.class), runManager,
                mock(TransactionTemplate.class), mock(ChatMessageDomainService.class),
                mock(ChatMessageHitlDomainService.class), conversations,
                mock(ChatRequestTraceDomainService.class));
        ChatConversation conversation = ChatConversation.builder()
                .userId("user-1")
                .conversationId("conversation-1")
                .activeRunId("pipeline-1")
                .build();
        when(conversations.findByUseIdAndConversationId("user-1", "conversation-1"))
                .thenReturn(conversation);
        when(runManager.cancel(conversation.getTaskId(), "pipeline-1"))
                .thenReturn(ReactiveRunManager.CancelResult.NOT_FOUND);

        service.interrupt(new ConversationInterruptRequest("user-1", "conversation-1"));

        verify(runManager).cancel(conversation.getTaskId(), "pipeline-1");
        verify(conversations).clearActiveRunIdIfMatch("conversation-1", "pipeline-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void replayUsesPersistedOriginAndCheckpointWithNewTraceRunId() {
        ChatMessageConverter converter = mock(ChatMessageConverter.class);
        ActiveAgentSessionStore activeAgentStore = mock(ActiveAgentSessionStore.class);
        AgentExecutePipeline pipeline = mock(AgentExecutePipeline.class);
        ChatStreamOutputAdapter outputAdapter = mock(ChatStreamOutputAdapter.class);
        ReactiveRunManager runManager = mock(ReactiveRunManager.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        ChatMessageDomainService messages = mock(ChatMessageDomainService.class);
        ChatMessageHitlDomainService hitls = mock(ChatMessageHitlDomainService.class);
        ChatConversationDomainService conversations = mock(ChatConversationDomainService.class);
        ChatRequestTraceDomainService traces = mock(ChatRequestTraceDomainService.class);
        ConversationApplicationService service = new ConversationApplicationService(
                converter, activeAgentStore, pipeline, outputAdapter, runManager,
                transactions, messages, hitls, conversations, traces);

        ChatConversation conversation = ChatConversation.builder()
                .conversationId("conversation-1").userId("user-1").build();
        ChatMessage approval = ChatMessage.builder()
                .messageId("message-1")
                .conversationId("conversation-1")
                .runId("segment-trace-1")
                .role(ChatRole.AGENT_HITL)
                .content("Agent 即将执行以下操作，请确认")
                .build();
        ChatMessageHitl hitl = ChatMessageHitl.builder()
                .messageId("message-1")
                .hitlId("hitl-1")
                .originRunId("original-agent-run")
                .checkpointId("checkpoint-1")
                .status(ChatMessageHitlStatus.PENDING)
                .build();
        when(conversations.findByUseIdAndConversationId("user-1", "conversation-1")).thenReturn(conversation);
        when(messages.findHitlMessage("conversation-1", "message-1"))
                .thenReturn(approval);
        when(hitls.getById("message-1")).thenReturn(hitl);
        when(hitls.changeStatus("message-1", ChatMessageHitlStatus.PENDING, ChatMessageHitlStatus.RESUMING))
                .thenReturn(true);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        when(traces.save(any())).thenReturn(true);
        when(messages.save(any())).thenReturn(true);
        when(traces.updateState(any(), eq(TaskState.PROCESS))).thenReturn(true);
        when(conversations.updateActiveRunId("conversation-1", "pipeline-2")).thenReturn(true);
        ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run = mock(ReactiveTaskRun.class);
        when(run.runId()).thenReturn("pipeline-2");
        when(run.state()).thenReturn(ReactiveTaskState.COMPLETED);
        when(pipeline.run(any(), any())).thenReturn(run);
        when(runManager.register(conversation.getTaskId(), run))
                .thenReturn(ReactiveRunManager.Registration.REGISTERED);
        when(outputAdapter.adapt(run, new CandidateOwner("user-1", "conversation-1")))
                .thenReturn(Flux.empty());

        ConversationReplayRequest request = new ConversationReplayRequest();
        request.setUserId("user-1");
        request.setConversationId("conversation-1");
        request.setMessageId("message-1");
        request.setAction(AgentApprovalAction.APPROVE);
        service.replay(request).blockLast();

        var savedTrace = org.mockito.ArgumentCaptor.forClass(ChatRequestTrace.class);
        verify(traces).save(savedTrace.capture());
        var agentRequest = org.mockito.ArgumentCaptor.forClass(AgentRequest.class);
        verify(pipeline).run(agentRequest.capture(), any());
        assertNotEquals("segment-trace-1", savedTrace.getValue().getRunId());
        assertEquals(savedTrace.getValue().getRunId(), agentRequest.getValue().getRunId());
        assertEquals("original-agent-run", agentRequest.getValue().getHitlRequestInfo().getOriginRunId());
        assertEquals("checkpoint-1", agentRequest.getValue().getHitlRequestInfo().getCheckpointId());
        assertEquals("hitl-1", agentRequest.getValue().getHitlRequestInfo().getHitlId());
        assertEquals(AgentApprovalAction.APPROVE, agentRequest.getValue().getHitlRequestInfo().getDecision());
        verify(hitls).changeStatus("message-1", ChatMessageHitlStatus.PENDING, ChatMessageHitlStatus.RESUMING);
    }

    @Test
    void missingApprovalNeverFallsBackToOrdinaryChat() {
        ChatConversationDomainService conversations = mock(ChatConversationDomainService.class);
        ChatMessageDomainService messages = mock(ChatMessageDomainService.class);
        ChatMessageHitlDomainService hitls = mock(ChatMessageHitlDomainService.class);
        AgentExecutePipeline pipeline = mock(AgentExecutePipeline.class);
        ConversationApplicationService service = new ConversationApplicationService(
                mock(ChatMessageConverter.class), mock(ActiveAgentSessionStore.class), pipeline,
                mock(ChatStreamOutputAdapter.class), mock(ReactiveRunManager.class),
                mock(TransactionTemplate.class), messages, hitls, conversations, mock(ChatRequestTraceDomainService.class));
        when(conversations.findByUseIdAndConversationId("user-1", "conversation-1"))
                .thenReturn(ChatConversation.builder().conversationId("conversation-1").userId("user-1").build());
        ConversationReplayRequest request = new ConversationReplayRequest();
        request.setUserId("user-1");
        request.setConversationId("conversation-1");
        request.setMessageId("missing");
        request.setAction(AgentApprovalAction.APPROVE);

        assertThrows(RuntimeException.class, () -> service.replay(request).blockLast());
        verifyNoInteractions(pipeline);
        verify(messages, never()).save(any());
    }

    @Test
    void failedAndApprovalRejectedResultsPersistTraceWithoutCompleteInfo() {
        for (AgentRunState state : List.of(AgentRunState.FAILED, AgentRunState.APPROVAL_REJECTED)) {
            ResultFixture fixture = new ResultFixture();

            fixture.handle(state, null);

            assertEquals(TaskState.FAILED, fixture.trace.getState());
            verify(fixture.traces).updateById(same(fixture.trace));
            // 只有请求开始时保存的用户消息；失败结果不构造Agent回复。
            verify(fixture.messages, times(1)).saveBatch(any());
            verifyNoInteractions(fixture.activeAgentStore);
        }
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void approvalPersistsMessageAndOneToOneHitlExtension() {
        ResultFixture fixture = new ResultFixture();
        when(fixture.activeAgentStore.getActiveAgent(any(), any())).thenReturn(Optional.empty());
        HumanInTheLoopInfo approval = HumanInTheLoopInfo.builder()
                .id("checkpoint-1")
                .originRunId("origin-run-1")
                .sourceAgent("masterAgent")
                .kind(HumanInTheLoopKind.APPROVAL)
                .data(Map.of("tool", "bookFlight"))
                .build();

        fixture.handle(AgentRunState.WAITING_APPROVAL, null, List.of(approval));

        var messageBatches = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(fixture.messages, times(2)).saveBatch(messageBatches.capture());
        List<ChatMessage> approvalMessages = (List<ChatMessage>) messageBatches.getAllValues().get(1);
        assertEquals(1, approvalMessages.size());
        assertEquals(ChatRole.AGENT_HITL, approvalMessages.getFirst().getRole());
        assertEquals("Agent 即将执行以下操作，请确认", approvalMessages.getFirst().getContent());

        var hitlBatch = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(fixture.hitls).saveBatch(hitlBatch.capture());
        List<ChatMessageHitl> details = (List<ChatMessageHitl>) hitlBatch.getValue();
        assertEquals(1, details.size());
        assertEquals(approvalMessages.getFirst().getMessageId(), details.getFirst().getMessageId());
        assertEquals("checkpoint-1", details.getFirst().getHitlId());
        assertEquals("origin-run-1", details.getFirst().getOriginRunId());
        assertEquals(ChatMessageHitlStatus.PENDING, details.getFirst().getStatus());
    }

    @Test
    void inputRequiredPersistsOnlyOrdinaryMessage() {
        ResultFixture fixture = new ResultFixture();
        when(fixture.activeAgentStore.getActiveAgent(any(), any())).thenReturn(Optional.empty());
        HumanInTheLoopInfo inputRequired = HumanInTheLoopInfo.builder()
                .id("input-1")
                .originRunId("origin-run-1")
                .sourceAgent("masterAgent")
                .kind(HumanInTheLoopKind.INPUT_REQUIRED)
                .question("请补充出发日期")
                .build();

        fixture.handle(AgentRunState.COMPLETED, null, List.of(inputRequired));

        verify(fixture.messages, times(2)).saveBatch(any());
        verify(fixture.hitls, never()).saveBatch(any());
    }

    @Test
    void traceWriteFailurePropagatesFromResultHandler() {
        ResultFixture fixture = new ResultFixture();
        when(fixture.traces.updateById(any())).thenReturn(false);

        assertThrows(RuntimeException.class, () -> fixture.handle(AgentRunState.FAILED, null));

        verify(fixture.traces).updateById(same(fixture.trace));
    }

    @Test
    void messageWriteFailurePropagatesBeforeTraceUpdate() {
        ResultFixture fixture = new ResultFixture();
        when(fixture.activeAgentStore.getActiveAgent(any(), any())).thenReturn(Optional.empty());
        when(fixture.messages.saveBatch(any())).thenReturn(false);

        assertThrows(RuntimeException.class, () -> fixture.handle(AgentRunState.COMPLETED,
                AgentCompleteInfo.builder().finalAnswer("已完成").build()));

        verify(fixture.messages, times(2)).saveBatch(any());
        verify(fixture.traces, never()).updateById(any());
    }

    private static final class ResultFixture {
        private final ChatMessageConverter converter = mock(ChatMessageConverter.class);
        private final ActiveAgentSessionStore activeAgentStore = mock(ActiveAgentSessionStore.class);
        private final AgentExecutePipeline pipeline = mock(AgentExecutePipeline.class);
        private final ChatStreamOutputAdapter outputAdapter = mock(ChatStreamOutputAdapter.class);
        private final ReactiveRunManager runManager = mock(ReactiveRunManager.class);
        private final TransactionTemplate transactions = mock(TransactionTemplate.class);
        private final ChatMessageDomainService messages = mock(ChatMessageDomainService.class);
        private final ChatMessageHitlDomainService hitls = mock(ChatMessageHitlDomainService.class);
        private final ChatConversationDomainService conversations = mock(ChatConversationDomainService.class);
        private final ChatRequestTraceDomainService traces = mock(ChatRequestTraceDomainService.class);
        private final ConversationApplicationService service = new ConversationApplicationService(
                converter, activeAgentStore, pipeline, outputAdapter, runManager,
                transactions, messages, hitls, conversations, traces);
        private ReactiveResultHandler<AgentExecutePipeline.PipelineResult> resultHandler;
        private ChatRequestTrace trace;

        @SuppressWarnings("unchecked")
        private ResultFixture() {
            when(transactions.execute(any())).thenAnswer(invocation -> {
                TransactionCallback<?> callback = invocation.getArgument(0);
                return callback.doInTransaction(mock(TransactionStatus.class));
            });
            doAnswer(invocation -> {
                Consumer<TransactionStatus> action = invocation.getArgument(0);
                action.accept(mock(TransactionStatus.class));
                return null;
            }).when(transactions).executeWithoutResult(any());
            when(conversations.save(any())).thenReturn(true);
            when(traces.save(any())).thenReturn(true);
            when(traces.updateById(any())).thenReturn(true);
            when(messages.saveBatch(any())).thenReturn(true);
            when(hitls.saveBatch(any())).thenReturn(true);
            when(converter.convertAgentInputContent(any())).thenReturn(AgentInputContent.builder()
                    .type(AgentInputContentType.TEXT).text("请安排出差").build());
            ReactiveTaskRun<String, AgentExecutePipeline.PipelineResult> run = mock(ReactiveTaskRun.class);
            when(pipeline.run(any(), any())).thenAnswer(invocation -> {
                resultHandler = invocation.getArgument(1);
                return run;
            });

            service.stream(ConversationStreamRequest.builder()
                    .userId("user-1")
                    .messages(List.of(ChatMessageRequest.builder()
                            .messageType(ChatMessageContentType.TEXT)
                            .content("请安排出差")
                            .build()))
                    .build());
            var savedTrace = org.mockito.ArgumentCaptor.forClass(ChatRequestTrace.class);
            verify(traces).save(savedTrace.capture());
            trace = savedTrace.getValue();
        }

        private void handle(AgentRunState state, AgentCompleteInfo completeInfo) {
            handle(state, completeInfo, List.of());
        }

        private void handle(AgentRunState state, AgentCompleteInfo completeInfo,
                            List<HumanInTheLoopInfo> hitlInfos) {
            AgentRunResult masterResult = AgentRunResult.builder()
                    .runId(trace.getRunId())
                    .conversationId(trace.getConversationId())
                    .state(state)
                    .completeInfo(completeInfo)
                    .humanInTheLoopInfos(hitlInfos)
                    .build();
            resultHandler.handle(new AgentExecutePipeline.PipelineResult(
                    "pipeline-1", null, null, masterResult)).block();
        }
    }
}
