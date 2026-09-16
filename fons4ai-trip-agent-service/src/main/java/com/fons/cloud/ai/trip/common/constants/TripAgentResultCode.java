package com.fons.cloud.ai.trip.common.constants;

import com.fons.cloud.common.result.Result;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 差旅智能Agent业务错误码
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum TripAgentResultCode implements Result {

    USER_NOT_EXIST("TA100001", "用户不存在"),
    PASSWORD_INCORRECT("TA100002", "用户名或密码错误"),
    LOGIN_EXPIRED("TA100003", "登录已过期，请重新登录"),
    CHAT_MESSAGE_IS_EMPTY("TA100004", "内容不能为空"),

    APPROVAL_RECORD_NOT_EXIST("TA200001", "审批记录不存在"),
    TRAVEL_ORDER_NOT_EXIST("TA200002", "差旅单不存在"),
    TRAVEL_ORDER_STATUS_NOT_EXPECTED("TA200003", "差旅单状态不符合预期"),
    TRAVEL_ORDER_STATUS_NOT_SUPPORT_CANCEL("TA200004", "差旅单状态不支持取消"),
    TRAVEL_ORDER_CANCEL_NEED_USER_SECOND_CONFIRM("TA200005", "差旅单取消需要用户二次确认"),

    ;

    /**
     * 错误码
     */
    private final String code;

    /**
     * 错误信息
     */
    private final String message;

}
