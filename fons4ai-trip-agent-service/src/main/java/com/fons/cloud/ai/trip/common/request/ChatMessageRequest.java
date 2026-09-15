package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.common.request.BaseRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

/**
 * @author hongqy
 */
@Getter
@Setter
@ToString
public class ChatMessageRequest extends BaseRequest {

    /**
     * 发送的内容
     */
    @NotBlank(message = "内容不能为空")
    private String content;

    /**
     * 消息类型
     */
    @NotNull(message = "消息类型不能为空")
    private ChatMessageContentType messageType;

}
