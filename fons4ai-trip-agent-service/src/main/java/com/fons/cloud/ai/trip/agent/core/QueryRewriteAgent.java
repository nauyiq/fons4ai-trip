package com.fons.cloud.ai.trip.agent.core;

import com.fons.cloud.ai.trip.infrastructure.config.properties.QueryRewriteAgentConfigProperties;
import com.fons.cloud.ai.trip.infrastructure.prompt.PromptLoader;
import com.fons.cloud.ai.trip.infrastructure.util.ExtractUtils;
import com.fons.cloud.ai.trip.infrastructure.util.ModelFacade;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * 问题重写Agent
 * @author hongqy
 */
@Slf4j
@Component
public class QueryRewriteAgent extends AgentBase {
    private final Model model;
    private final String systemPrompt;

    public QueryRewriteAgent(QueryRewriteAgentConfigProperties properties) {
        super(TripAgent.QUERY_REWRITE_AGENT.getAgentName(), TripAgent.QUERY_REWRITE_AGENT.getDescription());
        this.model = ModelFacade.getModel(properties.getModelType());
        boolean usingDefaultPrompt = StringUtils.isBlank(properties.getSystemPrompt());
        this.systemPrompt = usingDefaultPrompt ? PromptLoader.loadRequired("prompt/query_rewrite_agent_system_prompt.md") : properties.getSystemPrompt();
        log.info("[QueryRewriteAgent] 问题重写Agent初始化完成， modelType:{}, usingDefaultPrompt:{}", properties.getModelType(), usingDefaultPrompt);
    }

    @Override
    protected Mono<Msg> doCall(List<Msg> msgs) {
        List<Msg> messages = new ArrayList<>();
        // 注入系统提示词
        messages.add(Msg.builder()
                        .role(MsgRole.SYSTEM)
                        .name("system")
                        .content(TextBlock.builder().text(systemPrompt).build()).build());
        // 注入原文消息
        messages.addAll(msgs);

        return model.stream(messages, null, null)
                .collectList()
                .map(responses -> Msg.builder()
                        .name(TripAgent.QUERY_REWRITE_AGENT.getAgentName())
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text(ExtractUtils.extractText(responses)).build())
                        .build());
    }

    @Override
    protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
        return Mono.just(Msg.builder()
                .name(TripAgent.QUERY_REWRITE_AGENT.getAgentName())
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text("已停止问题改写。").build())
                .generateReason(GenerateReason.INTERRUPTED)
                .build());
    }
}
