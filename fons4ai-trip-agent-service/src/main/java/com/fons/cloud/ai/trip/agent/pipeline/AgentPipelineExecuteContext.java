package com.fons.cloud.ai.trip.agent.pipeline;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.agent.model.request.AgentInputContent;
import com.fons.cloud.ai.agent.model.request.AgentInputContentType;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.response.AgentExecutionSummary;
import com.fons.cloud.ai.agent.api.AgentRun;
import java.util.ArrayList;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.Getter;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * Agent管道工作流执行上下文
 * <p>
 *    除了执行任务节点有中断恢复的语义，其他LLM辅助节点不会有中断恢复。
 *    如果分析任务给用户中断了， 后续用户需要继续任务时则分析任务重新开始即可
 * </p>
 * @author hongqy
 */
@Getter
public final class AgentPipelineExecuteContext {

    /**
     * 用户请求Agent的原始请求
     */
    private final AgentRequest userInput;

    /**
     * 决定管道工作流中需要走的下一个步骤
     */
    private volatile AgentExecutionStep step = AgentExecutionStep.SEMANTICS_RECOGNITION_STEP;

    /**
     * 意图识别结果
     */
    private volatile IntentRecognitionResult recognitionResult;

    /**
     * 问题重写结果
     */
    private volatile String queryRewriteResult;

    /**
     * MasterAgent当前执行分段的权威结构化结果。
     *
     * <p>该结果可能表示正常完成、审批等待、拒绝、取消或失败，由Pipeline最终结果继续向上返回。</p>
     */
    private volatile AgentRunResult masterAgentResult;

    /** 当前请求两个分析步骤的用量，跳过/缓存命中保持 null，不复制缓存里的历史消耗。 */
    private volatile AgentExecutionSummary.ModelCall queryRewriteUsage;
    private volatile AgentExecutionSummary.ModelCall intentRecognitionUsage;

    /** 现有 Master 句柄，实际退出后读取最终摘要，覆盖取消时 relay 未返回结果的路径。 */
    private volatile AgentRun masterRun;

    public void queryRewriteUsage(AgentExecutionSummary.ModelCall usage) {
        this.queryRewriteUsage = preserveCallId(queryRewriteUsage, usage);
    }
    public void intentRecognitionUsage(AgentExecutionSummary.ModelCall usage) {
        this.intentRecognitionUsage = preserveCallId(intentRecognitionUsage, usage);
    }
    public void masterRun(AgentRun run) { this.masterRun = run; }

    /** 开始标记和最终返回属于同一次分析调用，更新用量时保留最初的调用身份。 */
    private AgentExecutionSummary.ModelCall preserveCallId(AgentExecutionSummary.ModelCall previous,
                                                          AgentExecutionSummary.ModelCall current) {
        if (previous == null || current == null) return current;
        return new AgentExecutionSummary.ModelCall(previous.callId(), current.agentName(), current.modelName(),
                current.inputTokens(), current.outputTokens(), current.cachedTokens(), current.completed());
    }

    /** 汇总现有步骤结果；不监听事件或重新执行模型。 */
    public AgentExecutionSummary executionSummary() {
        List<AgentExecutionSummary.ModelCall> calls = new ArrayList<>();
        if (queryRewriteUsage != null) calls.add(queryRewriteUsage);
        if (intentRecognitionUsage != null) calls.add(intentRecognitionUsage);
        AgentExecutionSummary master = masterRun == null ? null : masterRun.executionSummary();
        if (master == null && masterAgentResult != null) master = masterAgentResult.getExecutionSummary();
        if (master != null) calls.addAll(master.modelCalls());
        // 未支持统计是覆盖缺失，不是一次真实模型调用；保留已知分析小计并降低完整性。
        boolean masterUsageComplete = master == null ? masterRun == null : master.usageComplete();
        return new AgentExecutionSummary(calls, master == null ? List.of() : master.toolCalls(), masterUsageComplete);
    }


    private AgentPipelineExecuteContext(AgentRequest userInput) {
        this.userInput = userInput;
    }

    public static AgentPipelineExecuteContext create(AgentRequest userInput) {
        Assert.notNull(userInput, () -> SystemIntervalException.of("Agent请求不能为空"));
        AgentPipelineExecuteContext context = new AgentPipelineExecuteContext(userInput);
        if (userInput.getHitlRequestInfo() != null) {
            // 存在中断恢复请求 则直接路由到任务节点
            context.nextStep(AgentExecutionStep.EXECUTE_TASK_STEP);
        }
        return context;
    }

    public void recognition(IntentRecognitionResult recognitionResult) {
        this.recognitionResult = recognitionResult;
    }

    public void nextStep(AgentExecutionStep step) {
        this.step = step;
    }

    public void rewriteResult(String rewriteResult) {
        this.queryRewriteResult = rewriteResult;
    }

    public void masterAgentResult(AgentRunResult masterAgentResult) {
        Assert.notNull(masterAgentResult,
                () -> SystemIntervalException.of("MasterAgent执行结果不能为空"));
        this.masterAgentResult = masterAgentResult;
    }

    /**
     * 从用户请求中抽取用户输入文本
     * @return
     */
    public String extractUserTextInput() {
        List<AgentInputContent> contents = userInput.getContents();
        if (CollectionUtils.isNotEmpty(contents)) {
            AgentInputContent textInput = contents.stream()
                    .filter(e -> e.getType() == AgentInputContentType.TEXT)
                    .findFirst().orElse(null);
            if (textInput != null) {
                return textInput.getText();
            }
        }
        return null;
    }

    public String getUserId() {
        return userInput.getUserId();
    }

    public String getRunId() {
        return userInput.getRunId();
    }

    public String gerConversationId() {
        return userInput.getConversationId();
    }

}
