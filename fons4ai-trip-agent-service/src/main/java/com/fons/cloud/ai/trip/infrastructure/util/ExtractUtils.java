package com.fons.cloud.ai.trip.infrastructure.util;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;

/**
 * @author hongqy
 */
public class ExtractUtils {

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
