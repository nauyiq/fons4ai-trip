package com.fons.cloud.ai.trip.agent.core;

import com.fons.cloud.ai.agent.api.Agent;
import com.fons.cloud.ai.agent.api.AgentRegistry;
import com.fons.cloud.ai.agent.api.AgentRun;
import com.fons.cloud.ai.agent.infrastructure.session.ActiveAgentSessionStore;
import com.fons.cloud.ai.agent.model.request.AgentInputContent;
import com.fons.cloud.ai.agent.model.request.AgentInputContentType;
import com.fons.cloud.ai.agent.model.request.AgentRequest;
import com.fons.cloud.ai.trip.infrastructure.matcher.ContinuationsMatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Agent调度器
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentDispatcher {
    private final AgentRegistry agentRegistry;
    private final ActiveAgentSessionStore activeAgentSessionStore;

    /**
     * 执行一次Agent请求
     * @param request
     * @return
     */
    public AgentRun execute(AgentRequest request) {
        Optional<String> activeAgent = activeAgentSessionStore.getActiveAgent(request.getUserId(), request.getConversationId());
        if (activeAgent.isPresent() && isContinuation(request)) {
            // 继续上一轮对话的简单信号
            Agent agent = agentRegistry.getAgent(activeAgent.get());
            if (agent == null) {
                log.warn("Not found agent by registry, agentName:{}", activeAgent.get());
            } else {
                return agent.run(request);
            }
        }

        // 采用正常工作流编排执行Agent
        return null;
    }

    private boolean isContinuation(AgentRequest request) {
        List<AgentInputContent> contents = request.getContents();
        return contents.stream()
                .filter(content -> content.getType() == AgentInputContentType.TEXT)
                .anyMatch(e -> ContinuationsMatcher.isContinuation(e.getText()));

    }


}
