package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

/**
 * 对话会话
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_conversation")
public class ChatConversation extends BaseEntity {

    /**
     * 会话ID（同前端 sessionId）
     */
    @TableId
    private String conversationId;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 正在活跃的工作流Id
     */
    private String activeWorkerFlowId;

    /**
     * 逻辑删除标志
     */
    @TableLogic
    private Boolean deleted;

    public static ChatConversation create(String userId, String sessionId) {
        return ChatConversation.builder()
                .userId(userId)
                .conversationId(sessionId)
                .title("新会话")
                .deleted(false)
                .build();
    }
}
