package com.fons.cloud.ai.trip.common.vo;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class MessageInfo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 消息id
     */
    private String id;

    /**
     * 角色 {@link com.fons.cloud.ai.trip.common.constants.ChatRole}
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 思考内容
     */
    private String thinking;

    /**
     * 消息内容类型 {@link com.fons.cloud.ai.trip.common.constants.ChatMessageContentType}
     */
    private String messageContentType;

    /**
     * 额外消息
     */
    private Map<String, Object> extra;

    /**
     * 用户反馈：LIKE 点赞 / DISLIKE 点踩 / NULL 未反馈
     */
    private String feedback;

    /**
     * 反馈时间
     */
    private Long feedbackAt;

    /**
     * 审批ID
     */
    private String hitlId;

    /**
     * 审批状态 {@link com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus}
     */
    private String hitlStatus;

    /**
     * 人工审批的内容
     */
    private Map<String, Object> hitlData;
}
