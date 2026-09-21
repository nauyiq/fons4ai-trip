package com.fons.cloud.ai.trip.agent.pipeline;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.agent.model.request.AgentInputContent;
import com.fons.cloud.ai.agent.model.request.AgentInputContentType;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.Getter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * Agent管道工作流执行上下文
 * @author hongqy
 */
@Getter
public final class AgentPipelineExecuteContext {

    /**
     * 工作流ID
     */
    private final String workflowId;

    /**
     * 用户请求Agent的原始请求
     */
    private final AgentRequest userInput;

    /**
     * 决定管道工作流中需要走的下一个步骤
     */
    private volatile AgentExecutionStep step;

    /**
     * 意图识别结果
     */
    private volatile IntentRecognitionResult recognitionResult;

    /**
     * 问题重写结果
     */
    private volatile String queryRewriteResult;


    private AgentPipelineExecuteContext(String workflowId, AgentRequest userInput) {
        this.workflowId = workflowId;
        this.userInput = userInput;
    }

    public static AgentPipelineExecuteContext create(String workflowId, AgentRequest userInput) {
        return create(null, workflowId, userInput);
    }

    public static AgentPipelineExecuteContext create(String agentName, String workflowId, AgentRequest userInput) {
        Assert.notNull(userInput, () -> SystemIntervalException.of("Agent请求不能为空"));

        AgentPipelineExecuteContext context = new AgentPipelineExecuteContext(workflowId, userInput);
        context.nextStep(AgentExecutionStep.SEMANTICS_RECOGNITION_STEP);
        if (StringUtils.isNotBlank(agentName)) {
            AgentExecutionStep executionStep = AgentExecutionStep.getAgentExecutionStep(agentName);
            context.nextStep(executionStep);
        }
        return context;
    }

    public void recognition(IntentRecognitionResult recognitionResult) {
        this.recognitionResult = recognitionResult;
    }

    public void nextStep(AgentExecutionStep step) {
        this.step = step;
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
}
