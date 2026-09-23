package com.fons.cloud.ai.trip.agent.pipeline.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.request.HitlRequestInfo;
import com.fons.cloud.ai.agent.model.response.AgentResponse;
import com.fons.cloud.ai.trip.agent.core.IntentRecognitionAgent;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutionStep;
import com.fons.cloud.ai.trip.agent.pipeline.AgentPipelineExecuteContext;
import com.fons.cloud.ai.trip.common.constants.IntentCategory;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.ai.trip.infrastructure.converter.ChatMessageConverter;
import com.fons.cloud.ai.trip.infrastructure.repository.AnalysisIntentRepository;
import com.fons.cloud.ai.trip.infrastructure.util.SemanticsMatcherIntentRecognition;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.reactor.api.ReactiveTaskScope;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 意图识别步骤
 * <p>
 *     1. 如果存在问题改写结果, 则会再进行一次L1,L2的语义意图匹配。 识别不出时再调用LLM
 *     2. 上下文和缓存中存在意图识别结果时 直接成功
 * </p>
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRecognitionStepHandler extends AbstractAgentExecuteHandler {
    private final AnalysisIntentRepository repository;
    private final SemanticsMatcherIntentRecognition semanticsMatcherIntentRecognition;
    private final ChatMessageConverter chatMessageConverter;
    private final IntentRecognitionAgent intentRecognitionAgent;

    @Override
    protected Mono<ExecuteResult> execute(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope) {
        return Mono.defer(() -> {
            IntentRecognitionResult recognitionResult = context.getRecognitionResult();
            if (recognitionResult != null) {
                // 二次判断：上下文已经存在完整识别结果时，不再重复调用规则或LLM。
                log.warn("[INTENT_RECOGNITION_STEP]上下文已有意图识别结果， 直接进行业务任务。userId:{}, runId:{}", context.getUserId(), context.getRunId());
                return Mono.just(ExecuteResult.success(AgentExecutionStep.EXECUTE_TASK_STEP));
            }

            String analysisRunId = resolveAnalysisRunId(context);
            return findCachedIntent(context, analysisRunId)
                    .flatMap(cached -> {
                        if (cached.isPresent()) {
                            log.info("[INTENT_RECOGNITION_STEP]意图识别命中缓存。userId:{}, runId:{}", context.getUserId(), context.getRunId());
                            return completeRecognition(context, scope, cached.get());
                        }
                        return executeIntentRecognition(context, scope, analysisRunId);
                    });
        });
    }

    /**
     * 依次执行改写文本的规则识别和LLM兜底识别。
     */
    private Mono<ExecuteResult> executeIntentRecognition(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope, String analysisRunId) {
        String queryRewriteResult = context.getQueryRewriteResult();
        if (StringUtils.isNotBlank(queryRewriteResult)) {
            R<IntentRecognitionResult> recognition = semanticsMatcherIntentRecognition.semanticsRecognition(queryRewriteResult);
            if (recognition.isSuccess()) {
                log.info("[INTENT_RECOGNITION_STEP]问题改写后命中语义识别规则， userId:{}, runId:{}", context.getUserId(), analysisRunId);
                return persistAndCompleteRecognition(context, scope, analysisRunId, recognition.getData());
            }
        }

        scope.events().emit(AgentResponse.thinking("正在识别你的差旅需求……").toJson());
        return Mono.fromSupplier(() -> buildIntentRecognitionInput(context))
                .flatMap(intentRecognitionAgent::call)
                .flatMap(msg -> handleAgentResult(context, scope, analysisRunId, msg));
    }

    private UserMessage buildIntentRecognitionInput(AgentPipelineExecuteContext context) {
        String queryRewriteResult = context.getQueryRewriteResult();
        if (StringUtils.isNotBlank(queryRewriteResult)) {
            return UserMessage.builder()
                    .role(MsgRole.USER)
                    .name("user")
                    .textContent(queryRewriteResult)
                    .build();
        } else {
            return chatMessageConverter.convertUserMessage(context.getUserInput());
        }
    }

    private Mono<ExecuteResult> handleAgentResult(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope, String analysisRunId, Msg message) {
        if (isInterruptRecovery(message)) {
            log.info("[INTENT_RECOGNITION_STEP]意图识别Agent执行被中断");
            return Mono.just(ExecuteResult.failed());
        }

        IntentRecognitionResult recognitionResult = parseRecognitionResult(message == null ? null : message.getTextContent());
        return persistAndCompleteRecognition(context, scope, analysisRunId, recognitionResult);
    }

    /**
     * 保存新产生的识别结果，然后统一写回上下文并输出安全的识别摘要。
     */
    private Mono<ExecuteResult> persistAndCompleteRecognition(
            AgentPipelineExecuteContext context,
            ReactiveTaskScope<String> scope,
            String analysisRunId,
            IntentRecognitionResult recognitionResult) {
        if (recognitionResult == null) {
            return Mono.error(SystemIntervalException.of("意图识别结果不能为空"));
        }
        return Mono.fromRunnable(() -> repository.saveIntentRecognitionResult(
                        context.getUserId(), analysisRunId, recognitionResult))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> completeRecognition(context, scope, recognitionResult)));
    }

    /**
     * 将缓存、规则或LLM产生的结果收口到当前工作流上下文。
     */
    private Mono<ExecuteResult> completeRecognition(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope, IntentRecognitionResult recognitionResult) {
        context.recognition(recognitionResult);
        String summary = StringUtils.defaultIfBlank(
                recognitionResult.getOverallReason(),
                recognitionResult.getPrimary().getDescription());
        scope.events().emit(AgentResponse.thinking(summary).toJson());
        return Mono.just(ExecuteResult.success(AgentExecutionStep.EXECUTE_TASK_STEP));
    }

    /**
     * 按Trip意图协议严格解析LLM结果。
     */
    private IntentRecognitionResult parseRecognitionResult(String text) {
        try {
            String json = extractJsonBlock(text);
            if (StringUtils.isBlank(json)) {
                throw SystemIntervalException.of("意图识别Agent返回内容为空");
            }

            JSONObject root = JSON.parseObject(json);
            JSONArray intentArray = root.getJSONArray("intents");
            if (intentArray == null || intentArray.isEmpty()) {
                throw SystemIntervalException.of("意图识别结果中的intents不能为空");
            }

            List<IntentRecognitionResult.IntentItem> intents = new ArrayList<>(intentArray.size());
            Set<IntentCategory> categories = new HashSet<>();
            for (int index = 0; index < intentArray.size(); index++) {
                JSONObject item = intentArray.getJSONObject(index);
                IntentCategory category = parseCategory(item.getString("intent"));
                if (!categories.add(category)) {
                    throw SystemIntervalException.of("意图识别结果包含重复意图: " + category.getCode());
                }

                String targetAgent = item.getString("target_agent");
                if (!StringUtils.equals(category.getDefaultTargetAgent(), targetAgent)) {
                    throw SystemIntervalException.of("意图与目标Agent不匹配: " + category.getCode());
                }

                IntentRecognitionResult.Confidence confidence = parseConfidence(
                        item.getString("confidence"));
                String reason = item.getString("reason");
                if (StringUtils.isBlank(reason)) {
                    throw SystemIntervalException.of("意图识别结果中的reason不能为空");
                }
                intents.add(new IntentRecognitionResult.IntentItem(
                        category, targetAgent, confidence, reason.trim()));
            }

            IntentCategory primary = parseCategory(root.getString("primary_intent"));
            if (!categories.contains(primary)) {
                throw SystemIntervalException.of("primary_intent必须存在于intents中");
            }

            Boolean multiIntent = root.getBoolean("multi_intent");
            boolean expectedMultiIntent = intents.size() > 1;
            if (multiIntent == null || multiIntent != expectedMultiIntent) {
                throw SystemIntervalException.of("multi_intent与intents数量不一致");
            }

            String overallReason = root.getString("overall_reason");
            if (StringUtils.isBlank(overallReason)) {
                throw SystemIntervalException.of("意图识别结果中的overall_reason不能为空");
            }

            return new IntentRecognitionResult(
                    IntentRecognitionResult.Source.LLM,
                    intents,
                    primary,
                    multiIntent,
                    overallReason.trim(),
                    null);
        } catch (SystemIntervalException exception) {
            throw exception;
        } catch (Exception exception) {
            log.error("[INTENT_RECOGNITION_STEP]解析意图识别结果失败，响应内容：{}", text, exception);
            throw SystemIntervalException.of("意图识别Agent返回格式不正确", exception);
        }
    }

    private IntentCategory parseCategory(String value) {
        for (IntentCategory category : IntentCategory.values()) {
            if (category.getCode().equals(value)) {
                return category;
            }
        }
        throw SystemIntervalException.of("不支持的意图类型: " + value);
    }

    private IntentRecognitionResult.Confidence parseConfidence(String value) {
        return switch (StringUtils.defaultString(value)) {
            case "high" -> IntentRecognitionResult.Confidence.HIGH;
            case "medium" -> IntentRecognitionResult.Confidence.MEDIUM;
            case "low" -> IntentRecognitionResult.Confidence.LOW;
            default -> throw SystemIntervalException.of("不支持的意图置信度: " + value);
        };
    }

    /**
     * 从缓存中获取意图识别结果
     * @param context
     * @param analysisRunId
     * @return
     */
    private Mono<Optional<IntentRecognitionResult>> findCachedIntent(AgentPipelineExecuteContext context, String analysisRunId) {
        return Mono.fromCallable(() -> Optional.ofNullable(
                        repository.getIntentRecognitionResult(context.getUserId(), analysisRunId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public AgentExecutionStep currentStep() {
        return AgentExecutionStep.INTENT_RECOGNITION_STEP;
    }

    @Override
    public AgentExecutionStep nextStep() {
        return AgentExecutionStep.EXECUTE_TASK_STEP;
    }
}
