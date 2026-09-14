package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
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
     * 角色：user/agent/system
     */
    private ChatRole role;

    /**
     * 消息内容
     */
    private String content;

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
    private Integer deleted;

}
