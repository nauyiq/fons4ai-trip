package com.fons.cloud.ai.trip.domain.entity;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.TaskState;
import lombok.*;

/**
 * 一次请求中聊天轨迹。
 * <p>
 *     用户可能一次发送多次消息。  chat_message 只是其中一条。如果一个
 * </p>
 * @author hongqy
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_request_trace")
public class ChatRequestTrace {

    @TableId(type = IdType.AUTO)
    private Long traceId;

    /**
     * 本次请求的runId
     */
    private String runId;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 消耗多少token
     */
    private Long usedToken;

    /**
     * 分析后的消息内容  -> json结构, 包含问题改写和意图识别内容
     */
    private String analysisContent;

    /**
     * 调用了哪些工具
     */
    private String usedTools;

    /**
     * 任务执行的最终状态
     */
    private TaskState state;

    public static ChatRequestTrace create(String conversationId) {
        return ChatRequestTrace.builder()
                .runId(generateRunId())
                .conversationId(conversationId)
                .state(TaskState.init)
                .build();
    }


    public static String generateRunId() {
        return "run_" + IdUtil.fastSimpleUUID();
    }


}
