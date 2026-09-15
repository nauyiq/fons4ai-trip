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
