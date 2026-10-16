package com.fons.cloud.ai.trip.infrastructure.observability;

import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.request.HitlRequestInfo;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.agent.observability.annotation.AgentObserved;
import com.fons.cloud.ai.agent.observability.api.ObservationConvention;
import com.fons.cloud.ai.agent.observability.model.TraceRunContext;
import com.fons.cloud.ai.agent.observability.model.TraceStatus;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutePipeline;
import com.fons.cloud.ai.trip.agent.pipeline.AgentPipelineExecuteContext;
import com.fons.cloud.ai.trip.agent.pipeline.support.AbstractAgentExecuteHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Trip管道的观测映射约定。
 *
 * <p>约定把管道业务输入、输出与身份只读映射为观测数据：RUN声明对应
 * {@code trip.pipeline}根，STEP声明对应实际执行的分析步骤。本类是无每次请求可变
 * 字段的Singleton，不通过反射访问业务私有字段、不查询数据库、不发起远程调用。</p>
 *
 * <p>步骤采集与业务模板方法的跳过判断同口径：订阅期比较上下文当前步骤与处理器
 * 步骤，跳过的分析步骤不产生Span；Master步骤不加声明，由原生Harness中间件采集。</p>
 *
 * @author hongqy
 */
@Slf4j
@Component
public class TripObservationConvention implements ObservationConvention {

    /**
     * 处理管道RUN声明与三个分析Handler的STEP声明。
     *
     * @param invocation 当前拦截的调用声明
     * @return 目标为管道或分析处理器时返回 true
     */
    @Override
    public boolean supports(Invocation invocation) {
        Object target = invocation.target();
        return target instanceof AgentExecutePipeline || target instanceof AbstractAgentExecuteHandler;
    }

    /**
     * 提供管道根的稳定观测身份。
     *
     * @param invocation 当前拦截的调用声明
     * @param runtimeRunId common-reactor运行标识，作为pipelineRunId属性关联
     * @return 根观测身份
     */
    @Override
    public TraceRunContext runContext(Invocation invocation, String runtimeRunId) {
        AgentRequest request = (AgentRequest) invocation.arguments()[0];
        HitlRequestInfo hitl = request.getHitlRequestInfo();
        String originRunId = hitl == null ? null : hitl.getOriginRunId();
        return new TraceRunContext(request.getRunId(), request.getConversationId(),
                null, request.getUserId(), "trip.pipeline", "agentscope", originRunId,
                Map.of("pipelineRunId", runtimeRunId, "phase", originRunId == null ? "execute" : "resume"));
    }

    /**
     * 选择根输入快照：只保留请求的允许采集字段，不包含结果处理器或整个调用声明。
     *
     * @param invocation 当前拦截的调用声明
     * @return 输入快照
     */
    @Override
    public Object runInput(Invocation invocation) {
        AgentRequest request = (AgentRequest) invocation.arguments()[0];
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("runId", request.getRunId());
        input.put("conversationId", request.getConversationId());
        input.put("userId", request.getUserId());
        input.put("resume", request.getHitlRequestInfo() != null);
        return input;
    }

    /**
     * 读取管道分段成功执行的业务结果。
     *
     * <p>终态由Master权威结果映射；业务等待审批映射为SUSPENDED。结果为空、状态
     * 缺失或没有对应终态时不默认成功，按诊断结束为FAILED。</p>
     *
     * @param invocation 当前拦截的调用声明
     * @param result 管道执行结果
     * @return 观测终态与输出
     */
    @Override
    public Outcome runOutcome(Invocation invocation, Object result) {
        if (!(result instanceof AgentExecutePipeline.PipelineResult pipelineResult)) {
            log.warn("Trip管道结果类型缺失，按FAILED结束观测，result:{}",
                    result == null ? "none" : result.getClass().getName());
            return new Outcome(TraceStatus.FAILED, null);
        }
        AgentRunResult masterResult = pipelineResult.masterAgentResult();
        TraceStatus status = toTraceStatus(masterResult);
        if (status == null) {
            log.warn("Trip管道未获得有效终态，按FAILED结束观测，agentRunId:{}, state:{}",
                    masterResult == null ? "none" : masterResult.getRunId(),
                    masterResult == null ? "none" : masterResult.getState());
            return new Outcome(TraceStatus.FAILED, pipelineResult);
        }
        return new Outcome(status, pipelineResult);
    }

    /**
     * 订阅期判定分析步骤是否采集：与业务模板方法的跳过判断同口径。
     *
     * @param invocation 当前拦截的调用声明
     * @return 上下文当前步骤与处理器步骤一致时返回 true
     */
    @Override
    public boolean shouldObserveStep(Invocation invocation) {
        if (invocation.arguments().length == 0 || !(invocation.arguments()[0] instanceof AgentPipelineExecuteContext context)
                || !(invocation.target() instanceof AbstractAgentExecuteHandler handler)) {
            return false;
        }
        return context.getStep() == handler.currentStep();
    }

    /**
     * 提供分析步骤名称，沿用pipeline.&lt;step&gt;命名。
     *
     * @param invocation 当前拦截的调用声明
     * @return 步骤名称
     */
    @Override
    public String stepName(Invocation invocation) {
        AbstractAgentExecuteHandler handler = (AbstractAgentExecuteHandler) invocation.target();
        return "pipeline." + handler.currentStep().name().toLowerCase(Locale.ROOT);
    }

    /**
     * 读取分析步骤输出：只保存已产生的分析结果，不重复记录模型提示词。
     *
     * @param invocation 当前拦截的调用声明
     * @param value 步骤返回值，Mono&lt;Void&gt;场景可为空
     * @return 输出快照
     */
    @Override
    public Object stepOutput(Invocation invocation, Object value) {
        if (invocation.arguments().length == 0 || !(invocation.arguments()[0] instanceof AgentPipelineExecuteContext context)) {
            return null;
        }
        Map<String, Object> output = new LinkedHashMap<>();
        if (context.getQueryRewriteResult() != null) {
            output.put("rewrittenQuestion", context.getQueryRewriteResult());
        }
        if (context.getRecognitionResult() != null) {
            output.put("recognition", context.getRecognitionResult().toJsonMap());
        }
        output.put("nextStep", context.getStep());
        return output;
    }

    /**
     * 将Master权威结果映射为观测终态；非终态返回null，由统一入口按诊断处理。
     *
     * @param masterResult Master当前执行分段结果
     * @return 对应观测终态
     */
    private static TraceStatus toTraceStatus(AgentRunResult masterResult) {
        if (masterResult == null || masterResult.getState() == null) {
            return null;
        }
        return switch (masterResult.getState()) {
            case COMPLETED -> TraceStatus.SUCCEEDED;
            case WAITING_APPROVAL -> TraceStatus.SUSPENDED;
            case FAILED -> TraceStatus.FAILED;
            case CANCELLED -> TraceStatus.CANCELLED;
            case TIMED_OUT -> TraceStatus.TIMED_OUT;
            case REJECTED, APPROVAL_REJECTED -> TraceStatus.REJECTED;
            case CREATED, RUNNING -> null;
        };
    }
}
