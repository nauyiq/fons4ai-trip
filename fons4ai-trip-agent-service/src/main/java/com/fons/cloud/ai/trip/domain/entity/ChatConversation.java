package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.infrastructure.util.IdGenerator;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

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

    /** 没有有效文本输入时使用的默认标题。 */
    private static final String DEFAULT_TITLE = "新会话";

    /** 首次输入摘要的最大字符数，与前端临时标题保持一致。 */
    private static final int INITIAL_TITLE_MAX_LENGTH = 24;

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
        return create(userId, null);
    }

    /**
     * 使用首次有效文本创建会话，标题随会话一起持久化。
     *
     * @param userId 会话所属用户
     * @param initialText 首次请求中的有效文本；没有文本时保留默认标题
     */
    public static ChatConversation create(String userId, String initialText) {
        String title = StringUtils.normalizeSpace(initialText);
        if (StringUtils.isBlank(title)) {
            title = DEFAULT_TITLE;
        } else if (title.codePointCount(0, title.length()) > INITIAL_TITLE_MAX_LENGTH) {
            // 按 Unicode 码点截断，避免标题末尾的表情被截成半个代理字符。
            title = title.substring(0, title.offsetByCodePoints(0, INITIAL_TITLE_MAX_LENGTH));
        }
        return ChatConversation.builder()
                .userId(userId)
                .conversationId(IdGenerator.next(IdGenerator.Prefix.CONVERSATION))
                .title(title)
                .deleted(false)
                .build();
    }

    public String getTaskId() {
        return "TRIP-WORKER:" + getConversationId();
    }
}
