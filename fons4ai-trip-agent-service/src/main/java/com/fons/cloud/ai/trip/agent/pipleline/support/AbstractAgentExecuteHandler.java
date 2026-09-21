package com.fons.cloud.ai.trip.agent.pipleline.support;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.agent.pipleline.AgentExecuteContext;
import com.fons.cloud.ai.trip.agent.pipleline.AgentExecuteHandler;
import com.fons.cloud.ai.trip.agent.pipleline.AgentExecutionStep;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.common.base.exception.BizException;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.pipeline.PipelineConfigHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

/**
 * 抽象的Agent执行处理器
 *
 * @author hongqy
 */
@Slf4j
public abstract class AbstractAgentExecuteHandler implements AgentExecuteHandler {

    @Override
    public final void handle(AgentExecuteContext request) {
        // 基础数据校验
        Assert.notNull(request, () -> SystemIntervalException.of("Agent管道工作流执行上下文不能为空"));
        Assert.notNull(request.getStep(), () -> SystemIntervalException.of("Agent管道执行上下文步骤为空， 请检查数据"));
        Assert.isTrue(request.getUserInput() != null && CollectionUtils.isNotEmpty(request.getUserInput().getContents()),
                () -> SystemIntervalException.of("Agent请求为空， 请检查数据"));

        if (request.getStep() != currentStep()) {
            // 步骤不相等, 则跳过当前步骤
            log.debug("处理器步骤为={}， 上下文步骤={}， 步骤不一致跳过当前处理器", currentStep(), request.getStep());
            return;
        }

        try {
            log.debug("开始处理agent执行流程， 当前步骤：{}", currentStep());
            ExecuteResult execute = execute(request);
            log.debug("{}步骤执行结束， 执行结果：{}", currentStep(), JSON.toJSONString(execute));

            Assert.notNull(execute, () -> BusinessRuntimeException.of(TripAgentResultCode.AGENT_PIPELINE_HANDLE_RESULT_IS_NULL));

            if (!execute.success) {
                throw BusinessRuntimeException.of(TripAgentResultCode.AGENT_PIPELINE_HANDLE_RESULT_FAILED);
            }

            if (execute.nextStep != null) {
                // 处理器指定步骤的情况下 以处理器的步骤结果为主
                request.nextStep(execute.nextStep);
            } else {
                request.nextStep(nextStep());
            }

        } catch (BizException e) {
            log.warn("{}步骤执行发生业务异常，code:{}, message:{}", currentStep(), e.getCode(), e.getMessage());
            // 中断当前工作流
            PipelineConfigHolder.breakPipeline();
            // 处理器不处理异常 防止影响后续步骤
            throw e;
        } catch (Exception e) {
            log.error("{}步骤执行发生异常, message:{}", currentStep(), e.getMessage(), e);
            // 中断当前工作流
            PipelineConfigHolder.breakPipeline();
            // 处理器不处理异常 防止影响后续步骤
            throw e;
        }
    }

    /**
     * 由子类返回步骤执行结果， 父类负责编排处理结果
     * @param context
     * @return
     */
    protected abstract ExecuteResult execute(AgentExecuteContext context);


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

}
