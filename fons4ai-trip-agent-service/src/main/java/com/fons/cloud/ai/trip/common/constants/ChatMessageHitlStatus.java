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

    /** 凭证已事务保存，原Pipeline尚未交出执行权；不可展示或提交审批。 */
    PREPARED("PREPARED"),

    /** 原Pipeline已交出执行权，允许整批审批。 */
    PENDING("PENDING"),

    /** 恢复请求已占用审批，Agent正在执行 */
    RESUMING("RESUMING"),

    /** 审批回复已完成处理 */
    CONSUMED("CONSUMED"),

    /** 恢复已启动后失败或取消，不能自动重新开放可能已执行的业务操作。 */
    FAILED("FAILED");

    /** 存入数据库的值 */
    @EnumValue
    private final String code;
}
