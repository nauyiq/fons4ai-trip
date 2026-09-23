package com.fons.cloud.ai.trip.agent.pipeline.support;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.request.HitlRequestInfo;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecuteHandler;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutionStep;
import com.fons.cloud.ai.trip.agent.pipeline.AgentPipelineExecuteContext;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.reactor.api.ReactiveTaskScope;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

/**
 * 抽象的Agent执行处理器
 *
 * @author hongqy
 */
@Slf4j
public abstract class AbstractAgentExecuteHandler implements AgentExecuteHandler {

    @Override
    public final Mono<Void> handle(AgentPipelineExecuteContext request, ReactiveTaskScope<String> scope) {
        return Mono.defer(() -> {
            // 基础数据校验
            Assert.notNull(request, () -> SystemIntervalException.of("Agent管道工作流执行上下文不能为空"));
            Assert.notNull(request.getStep(), () -> SystemIntervalException.of("Agent管道执行上下文步骤为空， 请检查数据"));
            Assert.notNull(request.getUserInput(),
                    () -> SystemIntervalException.of("Agent请求为空， 请检查数据"));
            Assert.isTrue(CollectionUtils.isNotEmpty(request.getUserInput().getContents())
                            || request.getUserInput().getHitlRequestInfo() != null,
                    () -> SystemIntervalException.of("Agent请求内容和恢复信息不能同时为空"));

            if (request.getStep() != currentStep()) {
                // 步骤不相等, 则跳过当前步骤
                log.debug("处理器步骤为={}， 上下文步骤={}， 步骤不一致跳过当前处理器", currentStep(), request.getStep());
                return Mono.empty();
            }

            return execute(request, scope)
                    .switchIfEmpty(Mono.error(SystemIntervalException.of(currentStep() + "执行结果为空")))
                    .flatMap(result -> {
                        if (!result.success) {
                            return Mono.error(BusinessRuntimeException.of(TripAgentResultCode.AGENT_PIPELINE_HANDLE_RESULT_FAILED));
                        }
                        request.nextStep(result.nextStep == null? nextStep() : result.nextStep);
                        return Mono.empty();
                    });
        });
    }

    /**
     * 由子类返回步骤执行结果， 父类负责编排处理结果
     * @param context
     * @return
     */
    protected abstract Mono<ExecuteResult> execute(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope);


    @RequiredArgsConstructor
    protected static class ExecuteResult {

        private final boolean success;
        private final AgentExecutionStep nextStep;

        protected static ExecuteResult success() {
            return new ExecuteResult(true, null);
        }

        protected static ExecuteResult success(AgentExecutionStep nextStep) {
            return new ExecuteResult(true, nextStep);
        }

        protected static ExecuteResult failed() {
            return new ExecuteResult(false, null);
        }

    }

    /**
     * 判断 Agent 返回结果是否为优雅中断恢复提示。
     *
     * <p>AgentScope 被中断后会返回固定英文恢复文本，且当前版本未在 Msg 中标记
     * {@link GenerateReason#INTERRUPTED}，因此需要同时识别文本内容作为兜底。
     */
    protected boolean isInterruptRecovery(Msg result) {
        if (result == null) {
            return false;
        }
        return result.getGenerateReason() == GenerateReason.INTERRUPTED;
    }

    /**
     * 从可能包含 markdown 代码块的文本中提取 JSON 对象字符串。
     */
    protected String extractJsonBlock(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) {
                return trimmed.substring(start, end + 1);
            }
        }
        return trimmed;
    }

    /**
     * 获取实际运行的runId，
     * @param context
     * @return
     */
    protected String resolveAnalysisRunId(AgentPipelineExecuteContext context) {
        AgentRequest request = context.getUserInput();
        HitlRequestInfo hitl = request.getHitlRequestInfo();
        if (hitl != null && StringUtils.isNotBlank(hitl.getOriginRunId())) {
            return hitl.getOriginRunId();
        }
        return request.getRunId();
    }

}
