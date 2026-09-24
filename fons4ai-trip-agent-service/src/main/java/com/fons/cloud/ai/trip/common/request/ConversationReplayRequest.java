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


    @NotEmpty(message = "人工审批Id不能为空")
    private String hitlId;

    /**
     * 原始的runId
     */
    @NotEmpty(message = "原始中断恢复的runId不能为空")
    private String originRunId;

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
