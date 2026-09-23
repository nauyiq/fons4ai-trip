package com.fons.cloud.ai.trip.agent.pipeline;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.agent.model.request.AgentInputContent;
import com.fons.cloud.ai.agent.model.request.AgentInputContentType;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
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
