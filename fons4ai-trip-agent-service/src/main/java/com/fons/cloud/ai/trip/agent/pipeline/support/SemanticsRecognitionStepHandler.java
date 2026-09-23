package com.fons.cloud.ai.trip.agent.pipeline.support;

import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutionStep;
import com.fons.cloud.ai.trip.agent.pipeline.AgentPipelineExecuteContext;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.ai.trip.infrastructure.repository.AnalysisIntentRepository;
import com.fons.cloud.ai.trip.infrastructure.util.SemanticsMatcherIntentRecognition;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.reactor.api.ReactiveTaskScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 语义识别处理器
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticsRecognitionStepHandler extends AbstractAgentExecuteHandler {
    private final SemanticsMatcherIntentRecognition semanticsMatcherIntentRecognition;
    private final AnalysisIntentRepository analysisIntentRepository;

    @Override
    protected Mono<ExecuteResult> execute(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope) {
        return Mono.fromCallable(() -> {
            // 开始L1L2级别的语义识别
            String text = context.extractUserTextInput();
            return semanticsMatcherIntentRecognition.semanticsRecognition(text);
        }).subscribeOn(Schedulers.boundedElastic())
                .switchIfEmpty(Mono.error(SystemIntervalException.of("语义识别未返回结果")))
                .flatMap(recognition -> {
                    if (!recognition.isSuccess()) {
                        // 识别失败 直接下一步进行语义识别
                        log.info("语义识别失败， code:{}, message:{}", recognition.getCode(), recognition.getMessage());
                        return Mono.just(ExecuteResult.success());
                    }
                    IntentRecognitionResult result = recognition.getData();
                    if (result == null) {
                        return Mono.error(SystemIntervalException.of("语义识别命中但结果为空"));
                    }
                    // 先保存，MasterAgent的中间件才能在本轮和恢复请求中加载同一份结果。
                    log.info("语义识别命中， outcome：{}", JSON.toJSONString(result));
                    return Mono.fromRunnable(() -> analysisIntentRepository.saveIntentRecognitionResult(
                                    context.getUserId(), resolveAnalysisRunId(context), result))
                            .subscribeOn(Schedulers.boundedElastic())
                            .then(Mono.fromSupplier(() -> {
                                context.recognition(result);
                                return ExecuteResult.success(AgentExecutionStep.EXECUTE_TASK_STEP);
                            }));
                });
    }

    @Override
    public AgentExecutionStep currentStep() {
        return AgentExecutionStep.SEMANTICS_RECOGNITION_STEP;
    }

    @Override
    public AgentExecutionStep nextStep() {
        return AgentExecutionStep.QUERY_WRITING_STEP;
    }


}
