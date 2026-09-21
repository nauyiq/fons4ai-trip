package com.fons.cloud.ai.trip.agent.pipleline.support;

import com.fons.cloud.ai.agent.core.AgentTaskManager;
import com.fons.cloud.ai.agent.infrastructure.utils.AgentScopeMessageConverter;
import com.fons.cloud.ai.agent.model.request.AgentInputContent;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.trip.agent.pipleline.AgentExecuteContext;
import com.fons.cloud.ai.trip.agent.pipleline.AgentExecutionStep;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import com.fons.cloud.ai.trip.domain.entity.ChatMessage;
import com.fons.cloud.ai.trip.domain.service.ChatMessageDomainService;
import com.fons.cloud.ai.trip.infrastructure.repository.AnalysisIntentRepository;
import io.agentscope.core.message.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM进行问题改写步骤
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmQueryWritingStepHandler extends AbstractAgentExecuteHandler {
    private final AnalysisIntentRepository repository;
    private final AgentTaskManager agentTaskManager;
    private final ChatMessageDomainService chatMessageDomainService;
    private final AgentScopeMessageConverter agentScopeMessageConverter = AgentScopeMessageConverter.getInstance();

    // 默认查看历史消息的10条
    private static final int RECENT_MESSAGE_LIMIT = 10;

    @Override
    protected ExecuteResult execute(AgentExecuteContext context) {
        IntentRecognitionResult recognitionResult = context.getRecognitionResult();
        if (recognitionResult != null) {
            // 二次判断 如果已经意图识别结果 则没必要进行问题改写
            log.warn("上下文已有意图识别结果， 直接进行业务任务。userId:{}, workflowId:{}", context.getUserId(), context.getWorkflowId());
            return ExecuteResult.success(AgentExecutionStep.EXECUTE_TASK_STEP);
        }

        String rewriteResult = repository.getQueryRewriteResult(context.getUserId(), context.getWorkflowId());
        if (StringUtils.isNotBlank(rewriteResult)) {
            log.info("命中问题改写缓存， 直接使用缓存结果。 userId:{}, workflowId:{}", context.getUserId(), context.getWorkflowId());
        } else {
            //
        }
        return null;
    }


    private List<Msg> buildRewriteInput(AgentRequest input) {
        String conversationId = input.getConversationId();
        String userId = input.getUserId();

        List<AgentInputContent> contents = input.getContents();
        // 调用工作流消之前已经持久化了用户消息 这里获取历史消息时需要 + 请求的消息数
        List<ChatMessage> recentMessages = chatMessageDomainService.findRecentMessages(conversationId, contents.size() + RECENT_MESSAGE_LIMIT);

        // 这里copyAgent request
        return null;
    }



    @Override
    public AgentExecutionStep currentStep() {
        return AgentExecutionStep.LLM_QUERY_WRITING_STEP;
    }

    @Override
    public AgentExecutionStep nextStep() {
        return AgentExecutionStep.LLM_INTENT_RECOGNITION_STEP;
    }
}
