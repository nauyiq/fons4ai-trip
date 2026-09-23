package com.fons.cloud.ai.trip.agent.pipeline.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.response.AgentResponse;
import com.fons.cloud.ai.trip.agent.core.QueryRewriteAgent;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutionStep;
import com.fons.cloud.ai.trip.agent.pipeline.AgentPipelineExecuteContext;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import com.fons.cloud.ai.trip.infrastructure.converter.ChatMessageConverter;
import com.fons.cloud.ai.trip.infrastructure.repository.AnalysisIntentRepository;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.reactor.api.ReactiveTaskScope;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM进行问题改写步骤
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryWritingStepHandler extends AbstractAgentExecuteHandler {
    private final AnalysisIntentRepository repository;
    private final ChatMessageDomainService chatMessageDomainService;
    private final ChatMessageConverter chatMessageConverter;
    private final QueryRewriteAgent queryRewriteAgent;

    // 默认查看历史消息的10条
    private static final int RECENT_MESSAGE_LIMIT = 10;

    @Override
    protected Mono<ExecuteResult> execute(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope) {
        return Mono.defer(() -> {
            // 从上下文中获取意图识别结果
            IntentRecognitionResult recognitionResult = context.getRecognitionResult();
            if (recognitionResult != null) {
                // 二次判断 如果已经意图识别结果 则没必要进行问题改写
                log.warn("[QUERY_WRITING_STEP]上下文已有意图识别结果， 直接进行业务任务。userId:{}, runId:{}", context.getUserId(), context.getRunId());
                return Mono.just(ExecuteResult.success(AgentExecutionStep.EXECUTE_TASK_STEP));
            }

            String analysisRunId = resolveAnalysisRunId(context);
            return findCachedRewrite(context, analysisRunId)
                    .flatMap(cached -> {
                        if (StringUtils.isNotBlank(cached)) {
                            return handleCachedResult(context, cached);
                        }
                        return executeQueryRewrite(context, scope, analysisRunId);
                    });
        });
    }


    /**
     * 从缓存中获取意图重写结果
     * @param context
     * @param analysisRunId
     * @return
     */
    private Mono<String> findCachedRewrite(AgentPipelineExecuteContext context, String analysisRunId) {
        return Mono
                .fromCallable(() -> repository.getQueryRewriteResult(context.getUserId(), analysisRunId))
                .subscribeOn(Schedulers.boundedElastic())
                .defaultIfEmpty("");
    }

    /**
     * 缓存命中处理
     * @param context
     * @param rewriteResult
     * @return
     */
    private Mono<ExecuteResult> handleCachedResult(AgentPipelineExecuteContext context, String rewriteResult) {
        log.info("命中问题改写缓存。userId:{}, runId:{}", context.getUserId(), context.getRunId());
        context.rewriteResult(rewriteResult);
        // 继续进入意图识别
        return Mono.just(ExecuteResult.success());
    }

    /**
     * 调用LLM进行问题重写
     * @param context
     * @param scope
     * @param analysisRunId
     * @return
     */
    private Mono<ExecuteResult> executeQueryRewrite(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope, String analysisRunId) {
        // 发送一次thinking事件
        scope.events().emit(AgentResponse.thinking("正在结合历史上下文理解你的问题……").toJson());
        return Mono.fromCallable(() -> buildRewriteInput(context.getUserInput()))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(queryRewriteAgent::call)
                .flatMap(msg -> handleAgentResult(context, scope, analysisRunId, msg));
    }

    /**
     * 处理问题重写结果
     * @param context
     * @param scope
     * @param analysisRunId
     * @param message
     * @return
     */
    private Mono<ExecuteResult> handleAgentResult(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope, String analysisRunId, Msg message) {
        if (isInterruptRecovery(message)) {
            log.info("问题改写Agent执行被中断");
            return Mono.just(ExecuteResult.failed());
        }

        // 提取问题改写的结果
        String rewriteResult = parseRewrittenQuestion(message.getTextContent());
        log.info("问题改写完成, rewriteResult:{}", rewriteResult);
        if (StringUtils.isBlank(rewriteResult)) {
            return Mono.error(SystemIntervalException.of("问题改写Agent未返回有效的rewritten_question"));
        }

        // 问题改写结果放到上下文
        context.rewriteResult(rewriteResult);
        return Mono.fromRunnable(() -> repository.saveQueryRewriteResult(context.getUserId(), analysisRunId, rewriteResult))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.fromRunnable(() -> scope.events().emit(AgentResponse.thinking(rewriteResult).toJson())))
                .thenReturn(ExecuteResult.success());
    }


    private List<Msg> buildRewriteInput(AgentRequest input) {
        // 本轮消息已先入库，按 runId 排除后只取之前的历史；本轮原始输入最后追加。
        List<ChatMessage> history = chatMessageDomainService.findRecentMessages(
                input.getConversationId(), input.getRunId(), RECENT_MESSAGE_LIMIT);
        List<Msg> messages = new ArrayList<>(history.size() + 1);
        for (ChatMessage message : history) {
            ChatRole role = message.getRole();
            if (role == ChatRole.USER || role == ChatRole.USER_RESUME) {
                AgentRequest historicalRequest = AgentRequest.builder()
                        .userId(input.getUserId())
                        .conversationId(input.getConversationId())
                        .runId(message.getRunId())
                        .contents(List.of(chatMessageConverter.convertAgentInputContent(message)))
                        .build();
                messages.add(chatMessageConverter.convertUserMessage(historicalRequest));
            } else if (role == ChatRole.AGENT || role == ChatRole.AGENT_HITL) {
                messages.add(Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .name(StringUtils.defaultIfBlank(message.getAgentName(), "assistant"))
                        .content(TextBlock.builder().text(StringUtils.defaultString(message.getContent())).build())
                        .build());
            }
            // SYSTEM 不是用户和助手对话历史，避免覆盖问题改写Agent自己的系统协议。
        }
        messages.add(chatMessageConverter.convertUserMessage(input));
        return messages;
    }


    /**
     * 从 QueryRewritingAgent 的输出中提取 rewritten_question。
     * 如果解析失败，回退到使用原始文本。
     */
    private String parseRewrittenQuestion(String text) {
        try {
            String json = extractJsonBlock(text);
            if (json.isBlank()) {
                return text != null ? text.trim() : "";
            }
            JSONObject obj = JSON.parseObject(json);
            String rewritten = obj.getString("rewritten_question");
            if (rewritten != null && !rewritten.isBlank()) {
                return rewritten.trim();
            }
        } catch (Exception e) {
            log.warn("[PIPELINE] 解析改写结果失败，使用原始文本: {}", e.getMessage());
        }
        return text != null ? text.trim() : "";
    }

    @Override
    public AgentExecutionStep currentStep() {
        return AgentExecutionStep.QUERY_WRITING_STEP;
    }

    @Override
    public AgentExecutionStep nextStep() {
        return AgentExecutionStep.INTENT_RECOGNITION_STEP;
    }


}
