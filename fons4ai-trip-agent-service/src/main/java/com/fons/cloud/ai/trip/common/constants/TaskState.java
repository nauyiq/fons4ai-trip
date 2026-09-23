package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum TaskState {

    /**
     * 初始化
     */
    init("init"),

    /**
     * 执行中
     */
    PROCESS("process"),

    /**
     * 执行失败
     */
    FAILED("failed"),

    /**
     * 请求中断， 也是终态标识
     */
    INTERRUPT("interrupt"),

    /**
     * 等待审批， 也是终态标识
     */
    WAITING_APPROVAL("waiting_approval"),

    /**
     * 执行成功
     */
    SUCCESS("success"),


    ;

    @EnumValue
    private final String code;

}
