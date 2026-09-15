package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.common.request.BaseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * @author hongqy
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest extends BaseRequest {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话ID
     */
    @NotBlank(message = "会话Id不能为空")
    private String sessionId;

    /**
     * 是否中断请求
     */
    private boolean interrupt = false;

    /**
     * 消息列表
     */
    @Valid
    private List<ChatMessageRequest> messages;


}
