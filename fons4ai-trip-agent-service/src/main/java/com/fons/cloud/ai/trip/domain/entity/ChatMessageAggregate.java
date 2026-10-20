package com.fons.cloud.ai.trip.domain.entity;

import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.agent.model.response.AgentExecutionSummary;
import java.util.LinkedHashSet;
import com.fons.cloud.ai.trip.common.constants.TaskState;
import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * 一次聊天消息的实体聚合类
 * @author hongqy
 */
@Getter
@Setter
@RequiredArgsConstructor
public class ChatMessageAggregate {
    private final ChatConversation conversation;
    private final ChatRequestTrace trace;
    private final List<ChatMessage> messages;

    public String gerRunId() {
        return trace.getRunId();
    }

    public ChatMessageAggregate setAnalysisContent(String queryRewriteResult, IntentRecognitionResult recognitionResult) {
        // 讲分析结果保存到trace中
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("queryRewrite", queryRewriteResult);
        jsonObject.put("intentRecognition", recognitionResult);
        trace.setAnalysisContent(jsonObject.toJSONString());
        return this;
    }

    public ChatMessageAggregate setTraceTools(Set<String> tools) {
        if (CollectionUtils.isEmpty(tools)) {
            trace.setUsedTools("");
        } else {
            trace.setUsedTools(StringUtils.join(tools, ","));
        }
        return this;
    }

    public ChatMessageAggregate setTraceState(TaskState state) {
        trace.setState(state);
        return this;
    }

    /**
     * 将已发生的执行映射为业务请求摘要；不读取观测，也不从 LLM 正文推断工具或供应商。
     * 用量为已知小计，usageComplete 明确区分完整用量与取消后缺失的用量。
     */
    public ChatMessageAggregate setExecutionSummary(AgentExecutionSummary summary, String originRunId,
                                                   Set<String> registeredBusinessTools) {
        if (summary == null) return this;
        JSONObject data = new JSONObject();
        data.put("version", 1);
        data.put("originRunId", originRunId);
        data.put("modelCalls", summary.modelCalls());
        data.put("toolCalls", summary.toolCalls());
        data.put("usageComplete", summary.usageComplete());
        // SDK 的委派/协作工具保留在明细，业务工具摘要只列实际业务调用。
        Set<String> businessTools = summary.toolCalls().stream()
                .filter(AgentExecutionSummary.ToolCall::executed)
                .map(AgentExecutionSummary.ToolCall::toolName)
                .filter(StringUtils::isNotBlank)
                .filter(registeredBusinessTools::contains)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        trace.setUsedToken(summary.totalTokens());
        trace.setExecutionSummary(data.toJSONString());
        return setTraceTools(businessTools);
    }

    public String getConversationId() {
        return conversation.getConversationId();
    }
}
