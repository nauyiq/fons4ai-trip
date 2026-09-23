package com.fons.cloud.ai.trip.domain.entity;

import com.alibaba.fastjson2.JSONObject;
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
}
