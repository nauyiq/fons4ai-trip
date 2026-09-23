package com.fons.cloud.ai.trip.agent.pipeline;

import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.trip.agent.pipeline.support.IntentRecognitionStepHandler;
import com.fons.cloud.ai.trip.agent.pipeline.support.MasterExecuteTaskStepHandler;
import com.fons.cloud.ai.trip.agent.pipeline.support.QueryWritingStepHandler;
import com.fons.cloud.ai.trip.agent.pipeline.support.SemanticsRecognitionStepHandler;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.reactor.api.ReactiveResultHandler;
import com.fons.cloud.reactor.api.ReactiveTaskRun;
import com.fons.cloud.reactor.api.ReactiveTaskRunFactory;
import com.fons.cloud.reactor.api.ReactiveTaskScope;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Agent执行管道。
 *
 * <p>管道自身是一项普通响应式任务，负责按固定顺序执行分析步骤，并将MasterAgent的
 * {@link com.fons.cloud.ai.agent.api.AgentRun} 接入同一个根事件流。调用方只需订阅本方法返回的
 * {@link ReactiveTaskRun}，不需要分别订阅分析步骤和MasterAgent。</p>
 *
 * <p>恢复请求仍创建一次新的管道运行，但执行上下文会直接定位到任务执行步骤；
 * MasterAgent使用请求中的HITL信息恢复原Agent运行。</p>
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentExecutePipeline {

    /**
     * 普通响应式任务运行句柄工厂。
     */
    private final ReactiveTaskRunFactory reactiveTaskRunFactory;

    /**
     * 语义规则识别步骤。
     */
    private final SemanticsRecognitionStepHandler semanticsRecognitionStepHandler;

    /**
     * LLM问题改写步骤。
     */
    private final QueryWritingStepHandler queryWritingStepHandler;

    /**
     * LLM意图识别步骤。
     */
    private final IntentRecognitionStepHandler intentRecognitionStepHandler;

    /**
     * MasterAgent任务执行步骤。
     */
    private final MasterExecuteTaskStepHandler masterExecuteTaskStepHandler;

    /**
     * 按执行顺序排列的管道处理器快照。
     */
    private volatile List<AgentExecuteHandler> executeHandlers = List.of();

    /**
     * 初始化固定的管道步骤顺序。
     */
    @PostConstruct
    public void init() {
        executeHandlers = List.of(
                semanticsRecognitionStepHandler,
                queryWritingStepHandler,
                intentRecognitionStepHandler,
                masterExecuteTaskStepHandler);
    }

    /**
     * 创建一次尚未启动的Agent管道运行句柄。
     *
     * <p>创建本身不会执行任何步骤。首次订阅事件流或完成结果时，common-reactor运行时
     * 才会启动管道，并保证同一个Run只执行一次。</p>
     *
     * @param request Agent原始请求
     * @return 可供前端订阅的统一管道运行句柄
     */
    public ReactiveTaskRun<String, PipelineResult> run(AgentRequest request, ReactiveResultHandler<PipelineResult> handler) {
        return reactiveTaskRunFactory.create(scope -> Mono.defer(() -> {
            normalizeRequest(request);
            AgentPipelineExecuteContext context = AgentPipelineExecuteContext.create(request);
            log.info("开始执行Agent管道，pipelineRunId:{}, agentRunId:{}, resume:{}", scope.runId(), request.getRunId(), request.getHitlRequestInfo() != null);
            return executeHandlers(context, scope).then(Mono.fromSupplier(() -> buildResult(scope, context)));
        }), handler);
    }

    /**
     * 顺序执行当前管道中的全部处理器。
     *
     * <p>每个处理器会根据上下文当前步骤决定执行或跳过。使用{@code concatMap}保证
     * 下一处理器只能在上一处理器完成后启动，避免分析事件与MasterAgent事件乱序。</p>
     */
    private Mono<Void> executeHandlers(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope) {
        return Flux.fromIterable(executeHandlers)
                .concatMap(handler -> handler.handle(context, scope))
                .then();
    }

    /**
     * 补齐一次Agent运行所需的请求标识。
     *
     * <p>Agent runId和Pipeline runId属于不同领域：前者用于Agent会话、缓存和HITL恢复，
     * 后者标识本次工作流运行。因此普通请求未提供Agent runId时在这里单独生成。</p>
     */
    private void normalizeRequest(AgentRequest request) {
        if (request == null) {
            throw SystemIntervalException.of("Agent请求不能为空");
        }
        if (StringUtils.isBlank(request.getRunId())) {
            request.setRunId(UUID.randomUUID().toString().replace("-", ""));
        }
    }

    /**
     * 构造管道当前执行分段的结构化收口结果。
     */
    private PipelineResult buildResult(ReactiveTaskScope<String> scope, AgentPipelineExecuteContext context) {
        if (context.getStep() != AgentExecutionStep.END) {
            throw SystemIntervalException.of("Agent管道未执行到结束步骤，currentStep: " + context.getStep());
        }

        AgentRunResult masterAgentResult = context.getMasterAgentResult();
        if (masterAgentResult == null) {
            throw SystemIntervalException.of("Agent管道未产生MasterAgent执行结果");
        }
        log.info("Agent管道执行分段结束，pipelineRunId:{}, agentRunId:{}, agentState:{}", scope.runId(), masterAgentResult.getRunId(), masterAgentResult.getState());

        return new PipelineResult(
                scope.runId(),
                context.getQueryRewriteResult(),
                context.getRecognitionResult(),
                masterAgentResult);
    }

    /**
     * Agent管道一次执行分段的结构化结果。
     *
     * @param pipelineRunId 当前管道运行标识
     * @param queryRewriteResult 本次流程产生的问题改写结果；恢复请求直接进入MasterAgent时可为空
     * @param recognitionResult 本次流程产生的意图识别结果；恢复请求直接进入MasterAgent时可为空
     * @param masterAgentResult MasterAgent当前执行分段的权威结果
     */
    public record PipelineResult(
            String pipelineRunId,
            String queryRewriteResult,
            IntentRecognitionResult recognitionResult,
            AgentRunResult masterAgentResult) {
    }
}
