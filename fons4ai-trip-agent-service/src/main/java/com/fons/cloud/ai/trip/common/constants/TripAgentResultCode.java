package com.fons.cloud.ai.trip.common.constants;

import com.fons.cloud.common.result.Result;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 差旅智能Agent业务错误码
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum TripAgentResultCode implements Result {

    USER_NOT_EXIST("TA100001", "用户不存在"),
    PASSWORD_INCORRECT("TA100002", "用户名或密码错误"),
    LOGIN_EXPIRED("TA100003", "登录已过期，请重新登录"),
    CHAT_MESSAGE_IS_EMPTY("TA100004", "内容不能为空"),
    AGENT_PIPELINE_HANDLE_RESULT_IS_NULL("TA100005", "Agent管道处理器执行结果为空"),
    AGENT_PIPELINE_HANDLE_RESULT_FAILED("TA100006", "Agent管道处理器执行结果失败"),
    SEMANTICS_RECOGNITION_INPUT_IS_EMPTY("TA100007", "语义识别输入不能为空"),
    APPROVAL_RECORD_NOT_EXIST("TA100008", "审批记录不存在"),
    TRAVEL_ORDER_NOT_EXIST("TA100009", "差旅单不存在"),
    CONVERSATION_NOT_EXIST("TA100010", "会话不存在"),

    TRAVEL_ORDER_STATUS_NOT_EXPECTED("TA200001", "差旅单状态不符合预期"),
    TRAVEL_ORDER_STATUS_NOT_SUPPORT_CANCEL("TA200002", "差旅单状态不支持取消"),
    TRAVEL_ORDER_CANCEL_NEED_USER_SECOND_CONFIRM("TA200003", "差旅单取消需要用户二次确认"),
    TRAVEL_ORDER_STATUS_NOT_SUPPORT_MODIFY("TA200004", "差旅单状态不支持修改"),
    TRAVEL_ORDER_MODIFY_NEED_USER_SECOND_CONFIRM("TA200005", "差旅单修改需要用户二次确认"),
    TRAVEL_ORDER_INVALID_DATE_RANGE("TA200006", "差旅单日期范围无效"),

    SEMANTICS_RECOGNITION_HAS_MULTI_INTENT_SIGNAL("TA300001", "检测到并列/顺承连词，疑似多意图复合句"),
    SEMANTICS_RECOGNITION_HAS_AMBIGUOUS_INTENT_SIGNAL("TA300002", "多类命中跨子智能体意图，疑似多意图复合句"),


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
