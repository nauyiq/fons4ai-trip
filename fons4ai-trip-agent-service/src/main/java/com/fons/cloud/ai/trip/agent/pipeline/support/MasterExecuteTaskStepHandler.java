package com.fons.cloud.ai.trip.agent.pipeline.support;

import com.fons.cloud.ai.agent.api.Agent;
import com.fons.cloud.ai.agent.api.AgentRegistry;
import com.fons.cloud.ai.agent.api.AgentRun;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.trip.agent.core.TripAgent;
import com.fons.cloud.ai.trip.agent.pipeline.AgentExecutionStep;
import com.fons.cloud.ai.trip.agent.pipeline.AgentPipelineExecuteContext;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.reactor.api.ReactiveTaskScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * 交给Master agent执行任务
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterExecuteTaskStepHandler extends AbstractAgentExecuteHandler {
    private final AgentRegistry agentRegistry;

    @Override
    protected Mono<ExecuteResult> execute(AgentPipelineExecuteContext context, ReactiveTaskScope<String> scope) {
        return Mono.defer(() -> {
            String masterAgentName = TripAgent.MASTER_AGENT.getAgentName();
            Agent masterAgent = agentRegistry.getAgent(masterAgentName);
            if (masterAgent == null) {
                return Mono.error(SystemIntervalException.of("MasterAgent未注册，agentName: " + masterAgentName));
            }

            /*
             * 保留原始AgentRequest，确保普通请求内容以及HITL恢复请求中的originRunId、
             * checkpointId和审批决定完整传递给AgentScope适配器。
             */
            AgentRun masterRun = masterAgent.run(context.getUserInput());
            if (masterRun == null) {
                return Mono.error(SystemIntervalException.of(
                        "MasterAgent返回的AgentRun不能为空"));
            }

            log.info("开始执行MasterAgent，pipelineRunId:{}, agentRunId:{}", scope.runId(), masterRun.runId());

            /*
             * relay负责订阅并转发MasterAgent事件、等待权威AgentRunResult，
             * 并在Pipeline取消时向MasterAgent传播取消信号。
             */
            return scope.relay(masterRun)
                    .switchIfEmpty(Mono.error(SystemIntervalException.of("MasterAgent未返回AgentRunResult")))
                    .map(result -> completeMasterRun(context, result));
        });
    }

    /**
     * 保存MasterAgent当前执行分段的权威结果。
     *
     * <p>WAITING_APPROVAL、FAILED或CANCELLED等状态均是Agent领域的结构化结果，
     * Handler只负责保存和结束当前步骤，不将它们改写为Reactor错误信号。</p>
     */
    private ExecuteResult completeMasterRun(
            AgentPipelineExecuteContext context,
            AgentRunResult result) {
        if (result == null || result.getState() == null) {
            throw SystemIntervalException.of("MasterAgent返回了无效的AgentRunResult");
        }
        context.masterAgentResult(result);
        log.info("MasterAgent执行分段结束，agentRunId:{}, state:{}", result.getRunId(), result.getState());
        return ExecuteResult.success();
    }

    @Override
    public AgentExecutionStep currentStep() {
        return AgentExecutionStep.EXECUTE_TASK_STEP;
    }

    @Override
    public AgentExecutionStep nextStep() {
        return AgentExecutionStep.END;
    }
}
