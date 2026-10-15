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
    HITL_MESSAGE_NOT_EXIST("TA100011", "人工审批消息不存在或不属于当前会话"),
    HITL_MESSAGE_ALREADY_USED("TA100012", "人工审批消息已提交，不能重复回复"),
    HITL_EDIT_PARAMS_REQUIRED("TA100013", "编辑审批必须提供修改后的参数"),
    ITINERARY_PLAN_PAGE_NOT_EXIST("TA100014", "行程方案页面不存在或暂不可用"),
    HITL_BATCH_NOT_READY("TA100015", "本批审批尚未就绪，不能提交"),
    HITL_BATCH_REPLAY_REQUIRED("TA100016", "本批审批必须一次提交全部决定，不能通过单项入口恢复"),
    CONVERSATION_WAITING_APPROVAL("TA100017", "当前会话存在未完成审批，请先处理整批审批"),
    HITL_LEGACY_CREDENTIAL("TA100018", "历史审批缺少批次凭证，当前不能恢复"),
    HITL_BATCH_DECISIONS_INVALID("TA100019", "必须一次提交本批全部决定，不能缺项、重复或混入其他批次"),
    HITL_BATCH_VERSION_EXPIRED("TA100020", "审批批次版本已过期，请刷新会话"),
    HITL_BATCH_VALIDATION_FAILED("TA100021", "本批状态或审批参数不满足恢复条件，请检查后重新提交"),
    HITL_REQUEST_ID_ALREADY_USED("TA100022", "审批请求标识已经使用，请查询原批次状态，不能重复执行"),
    HITL_BATCH_RESUME_FAILED("TA100023", "本批恢复已失败或取消，需要人工处理，不能重复执行"),

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
