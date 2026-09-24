package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.infrastructure.util.IdGenerator;
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
     * 会话ID
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
    private String activeRunId;

    /**
     * 逻辑删除标志
     */
    @TableLogic
    private Boolean deleted;

    public static ChatConversation create(String userId) {
        return ChatConversation.builder()
                .userId(userId)
                .conversationId(IdGenerator.next(IdGenerator.Prefix.CONVERSATION))
                .title("新会话")
                .deleted(false)
                .build();
    }

    public String getTaskId() {
        return "TRIP-WORKER:" + getConversationId();
    }
}
