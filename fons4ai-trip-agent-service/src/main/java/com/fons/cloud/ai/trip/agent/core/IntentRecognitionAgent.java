package com.fons.cloud.ai.trip.agent.core;

import com.fons.cloud.ai.trip.infrastructure.config.properties.IntentRecognitionAgentConfigProperties;
import com.fons.cloud.ai.trip.infrastructure.prompt.PromptLoader;
import com.fons.cloud.ai.trip.infrastructure.util.ModelFacade;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 意图识别Agent
 * @author hongqy
 */
@Slf4j
@Component
public class IntentRecognitionAgent extends AgentBase {
    private final Model model;
    private final String systemPrompt;

    public IntentRecognitionAgent(IntentRecognitionAgentConfigProperties properties) {
        super(TripAgent.INTENT_RECOGNITION_AGENT.getAgentName(), TripAgent.INTENT_RECOGNITION_AGENT.getDescription());
        this.model = ModelFacade.getModel(properties.getModelType());
        boolean usingDefaultPrompt = StringUtils.isBlank(properties.getSystemPrompt());
        this.systemPrompt = usingDefaultPrompt ? PromptLoader.loadRequired("prompt/intent_recognition_agent_system_prompt.md") : properties.getSystemPrompt();
        log.info("[IntentRecognitionAgent] 意图识别Agent初始化完成， modelType:{}, usingDefaultPrompt:{}", properties.getModelType(), usingDefaultPrompt);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        return null;
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        return null;
    }
}
