package com.fons.cloud.ai.trip.domain.entity;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

import java.util.Date;

/**
 * 对话消息
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    /**
     * 消息ID
     */
    @TableId
    private String messageId;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 所属agent请求的id
     */
    private String runId;

    /**
     * 角色：user/agent/system
     */
    private ChatRole role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 消息类型
     */
    private ChatMessageContentType type;

    /**
     * Agent名称（role=agent 时）
     */
    private String agentName;

    /**
     * 扩展信息（进度快照/推荐问题等）
     */
    private String extra;

    /**
     * 用户反馈：LIKE 点赞 / DISLIKE 点踩 / NULL 未反馈
     */
    private String feedback;

    /**
     * 反馈时间
     */
    private Date feedbackAt;

    /**
     * 逻辑删除标志
     */
    @TableLogic
    private boolean deleted;

    public static ChatMessage create(String conversationId, ChatRole role, ChatMessageRequest request) {
        return ChatMessage.builder()
                .runId(IdUtil.fastSimpleUUID())
                .messageId(generateMessageId())
                .conversationId(conversationId)
                .content(request.getContent())
                .type(request.getMessageType())
                .role(role)
                .deleted(false)
                .build();
    }

    public static String generateMessageId() {
        return "msg_" + IdUtil.fastSimpleUUID();
    }




}
