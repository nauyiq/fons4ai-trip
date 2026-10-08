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
 *     仅保存{@code APPROVAL}类型的中断恢复信息，与{@code chat_message}通过
 *     {@code message_id}一对一关联。
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

    /**
     * HITL处理状态：PENDING/RESUMING/CONSUMED
     */
    private ChatMessageHitlStatus status;

    /**
     * Agent返回的审批展示数据，JSON结构
     */
    private String hitlData;

    /**
     * 根据Agent HITL信息创建消息扩展记录
     *
     * @param messageId 关联的聊天消息ID
     * @param info Agent返回的HITL信息
     * @return 消息HITL扩展实体
     */
    public static ChatMessageHitl create(String messageId, HumanInTheLoopInfo info) {
        return ChatMessageHitl.builder()
                .messageId(messageId)
                .hitlId(info.getId())
                .originRunId(info.getOriginRunId())
                // 当前框架返回的HITL ID就是恢复checkpoint；两个字段分开保存，避免语义耦合。
                .checkpointId(info.getId())
                .status(ChatMessageHitlStatus.PENDING)
                .hitlData(info.getData() == null ? null : JSON.toJSONString(info.getData()))
                .build();
    }
}
