package com.fons.cloud.ai.trip.agent.pipleline;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.agent.model.request.AgentInputContent;
import com.fons.cloud.ai.agent.model.request.AgentInputContentType;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 分析型Agent执行上下文
 * @author hongqy
 */
@Getter
public final class AgentExecuteContext {

    /**
     * 用户请求Agent的原始请求
     */
    private final AgentRequest userInput;

    /**
     * 决定管道工作流中需要走的下一个步骤
     */
    @Setter
    private AgentExecutionStep step;

    /**
     * 意图识别结果
     */
    private IntentRecognitionResult recognitionResult;

    private AgentExecuteContext(AgentRequest userInput) {
        this.userInput = userInput;
    }

    public static AgentExecuteContext create(AgentRequest userInput) {
        return create(null, userInput);
    }

    public static AgentExecuteContext create(String agentName, AgentRequest userInput) {
        Assert.notNull(userInput, () -> SystemIntervalException.of("Agent请求不能为空"));

        AgentExecuteContext context = new AgentExecuteContext(userInput);
        context.setStep(AgentExecutionStep.SEMANTICS_RECOGNITION_STEP);
        if (StringUtils.isNotBlank(agentName)) {
            AgentExecutionStep executionStep = AgentExecutionStep.getAgentExecutionStep(agentName);
            context.setStep(executionStep);
        }
        return context;
    }

    public void recognition(IntentRecognitionResult recognitionResult) {
        this.recognitionResult = recognitionResult;
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



}
