package com.fons.cloud.ai.trip.agent.pipleline.support;

import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.agent.pipleline.AgentExecuteContext;
import com.fons.cloud.ai.trip.agent.pipleline.AgentExecutionStep;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.ai.trip.infrastructure.util.SemanticsMatcherIntentRecognition;
import com.fons.cloud.common.result.R;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticsRecognitionStepHandler extends AbstractAgentExecuteHandler {
    private final SemanticsMatcherIntentRecognition semanticsMatcherIntentRecognition;

    @Override
    protected ExecuteResult execute(AgentExecuteContext context) {
        // 开始L1L2级别的语义识别
        String text = context.extractUserTextInput();
        R<IntentRecognitionResult> recognition = semanticsMatcherIntentRecognition.semanticsRecognition(text);

        if (recognition.isSuccess()) {
            // 识别成功 即命中L1或者L2策略 将命中结果保存到上下文 并且直接执行任务
            log.info("语义识别命中， outcome：{}", JSON.toJSONString(recognition.getData()));
            context.recognition(recognition.getData());
            return ExecuteResult.success(AgentExecutionStep.EXECUTE_TASK_STEP);
        } else {
            // 识别失败 直接下一步进行语义识别
            log.info("语义识别失败， code:{}, message:{}", recognition.getCode(), recognition.getMessage());
            return ExecuteResult.success();
        }
    }

    @Override
    public AgentExecutionStep currentStep() {
        return AgentExecutionStep.SEMANTICS_RECOGNITION_STEP;
    }

    @Override
    public AgentExecutionStep nextStep() {
        return AgentExecutionStep.LLM_QUERY_WRITING_STEP;
    }
}
