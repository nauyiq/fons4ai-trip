package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.ai.agent.model.request.AgentApprovalAction;
import com.fons.cloud.common.request.BaseRequest;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * 会话回复请求
 * @author hongqy
 */
@Getter
@Setter
public class ConversationReplayRequest extends BaseRequest {

    /**
     * 登录用户ID， 控制层赋值
     */
    private String userId;

    /**
     * 会话ID，不能为空
     */
    @NotEmpty(message = "会话Id不能为空")
    private String conversationId;


    /** 审批消息ID；服务端通过它读取可信的HITL恢复信息。 */
    @NotEmpty(message = "审批消息Id不能为空")
    private String messageId;

    /**
     * 人工审批的动作
     */
    @NotNull(message = "审批行为不能为空")
    private AgentApprovalAction action;

    /**
     * 恢复内容， 纯文本或者结构化的json数据。
     */
    private Map<String, Object> params;



}
