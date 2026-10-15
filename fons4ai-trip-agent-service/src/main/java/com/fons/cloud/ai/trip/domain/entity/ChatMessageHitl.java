package com.fons.cloud.ai.trip.domain.entity;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopInfo;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

/**
 * 对话消息HITL扩展信息。
 * <p>
 * 仅保存{@code APPROVAL}类型的中断恢复信息，与{@code chat_message}通过
 * {@code message_id}一对一关联。
 * </p>
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_message_hitl")
public class ChatMessageHitl extends BaseEntity {

    /**
     * 关联的聊天消息ID，同时作为本表主键
     */
    @TableId(type = IdType.INPUT)
    private String messageId;

    /** 审批所属会话，用于整批就绪和待审批入口检查。 */
    private String conversationId;

    /** 保存本批凭证的Pipeline运行ID，必须完成执行权交接后才能开放审批。 */
    private String pipelineRunId;

    /**
     * Agent返回的HITL标识
     */
    private String hitlId;

    /**
     * 产生本次中断的原始Agent运行ID
     */
    private String originRunId;

    /**
     * Agent恢复执行使用的检查点ID
     */
    private String checkpointId;

    /** 一次Run收口产生的审批批次，同批多条消息共用此值。 */
    private String pauseId;

    /** 框架批次保存版本，整批恢复时原样使用。 */
    private Long pauseVersion;

    /** 不透明恢复引用，原生父子路由保留在框架共享状态中。 */
    private String resumeRef;

    /**
     * HITL处理状态：PREPARED/PENDING/RESUMING/CONSUMED/FAILED
     */
    private ChatMessageHitlStatus status;

    /** 成功占用本批票证的提交标识，用于审计及重复请求识别。 */
    private String resumeRequestId;

    /** 本次恢复的Agent/Trace运行ID，所有状态更新必须匹配此持有者。 */
    private String resumeRunId;

    /**
     * Agent返回的审批展示数据，JSON结构
     */
    private String hitlData;

    /**
     * 根据Agent HITL信息创建消息扩展记录
     *
     * @param messageId 关联的聊天消息ID
     * @param info      Agent返回的HITL信息
     * @return 消息HITL扩展实体
     */
    public static ChatMessageHitl create(String messageId, HumanInTheLoopInfo info) {
        return create(messageId, info, null, null);
    }

    /** 保存完整可信凭证；新批次先准备，正式开放由应用层完成执行权交接后决定。 */
    public static ChatMessageHitl create(String messageId, HumanInTheLoopInfo info,
                                         String conversationId, String pipelineRunId) {
        return ChatMessageHitl.builder()
                .messageId(messageId)
                .conversationId(conversationId)
                .pipelineRunId(pipelineRunId)
                .hitlId(info.getId())
                .originRunId(info.getOriginRunId())
                .checkpointId(info.getCheckpointId())
                .pauseId(info.getPauseId())
                .pauseVersion(info.getPauseVersion())
                .resumeRef(info.getResumeRef())
                .status(ChatMessageHitlStatus.PREPARED)
                .hitlData(info.getData() == null ? null : JSON.toJSONString(info.getData()))
                .build();
    }
}
