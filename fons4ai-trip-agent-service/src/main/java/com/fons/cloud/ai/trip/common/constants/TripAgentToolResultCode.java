package com.fons.cloud.ai.trip.common.constants;

import com.fons.cloud.common.result.Result;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * agent执行参数响应码
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum TripAgentToolResultCode implements Result {

    SUCCESS("SUCCESS", "成功"),
    INVALID_PARAM("INVALID_PARAM", "无效的请求参数"),
    INVALID_DATE_RANGE("INVALID_DATE_RANGE", "无效的时间范围"),
    VERIFY_FAILED("VERIFY_FAILED", "验证失败"),
    ORDER_NOT_FOUND("ORDER_NOT_FOUND", "差旅单不存在"),
    INVALID_STATE("INVALID_STATE", "无效的订单状态"),
    NEED_USER_CONFIRM("NEED_USER_CONFIRM", "需要用户确认"),
    INTERNAL_ERROR("INTERNAL_ERROR", "内部异常"),

    ;

    private final String code;
    private final String message;

}
