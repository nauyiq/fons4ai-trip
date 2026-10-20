package com.fons.cloud.ai.trip.infrastructure.util;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.message.Msg;
import com.fons.cloud.ai.agent.model.response.AgentExecutionSummary;
import com.alibaba.fastjson2.JSON;
import java.util.Map;
import java.util.UUID;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * @author hongqy
 */
public class ExtractUtils {

    /** 分析 Agent 的一次模型执行用量，通过返回消息交给 Pipeline，不进入历史正文。 */
    public static final String MODEL_USAGE = "trip.modelUsage";

    public static Map<String, Object> usageMetadata(List<ChatResponse> responses, String agentName, String modelName) {
        ChatUsage usage = null;
        for (ChatResponse response : responses) {
            if (response.getUsage() != null) usage = response.getUsage();
        }
        // 流式 usage 可能是累计值，取最后一个有效值，不能将各片段相加。
        return Map.of(MODEL_USAGE, new AgentExecutionSummary.ModelCall(UUID.randomUUID().toString(),
                agentName, modelName, usage == null ? null : (long) usage.getInputTokens(),
                usage == null ? null : (long) usage.getOutputTokens(),
                usage == null ? null : (long) usage.getCachedTokens(), true));
    }

    public static AgentExecutionSummary.ModelCall modelUsage(Msg message) {
        Object value = message == null || message.getMetadata() == null ? null : message.getMetadata().get(MODEL_USAGE);
        if (value instanceof AgentExecutionSummary.ModelCall call) return call;
        return value == null ? null : JSON.parseObject(JSON.toJSONString(value), AgentExecutionSummary.ModelCall.class);
    }

    /**
     * 将流式 {@link ChatResponse} 中的所有 TextBlock 文本按顺序拼接为完整字符串。
     */
    public static String extractText(List<ChatResponse> responses) {
        if (CollectionUtils.isEmpty(responses)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ChatResponse response : responses) {
            List<ContentBlock> blocks = response.getContent();
            if (blocks == null) {
                continue;
            }
            for (ContentBlock block : blocks) {
                if (block instanceof TextBlock textBlock) {
                    sb.append(textBlock.getText());
                }
            }
        }
        return sb.toString();
    }

}
