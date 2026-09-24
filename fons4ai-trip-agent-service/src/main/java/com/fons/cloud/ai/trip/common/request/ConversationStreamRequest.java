package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.common.request.BaseRequest;
import jakarta.validation.Valid;
import lombok.*;

import java.util.List;

/**
 * 发起流式会话请求
 * @author hongqy
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationStreamRequest extends BaseRequest {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 消息列表
     */
    @Valid
    private List<ChatMessageRequest> messages;


}
