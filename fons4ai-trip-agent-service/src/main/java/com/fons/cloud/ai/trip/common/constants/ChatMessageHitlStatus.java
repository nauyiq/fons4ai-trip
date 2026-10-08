package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 聊天消息HITL审批的恢复状态
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ChatMessageHitlStatus {

    /** 等待用户审批 */
    PENDING("PENDING"),

    /** 恢复请求已占用审批，Agent正在执行 */
    RESUMING("RESUMING"),

    /** 审批回复已完成处理 */
    CONSUMED("CONSUMED");

    /** 存入数据库的值 */
    @EnumValue
    private final String code;
}
