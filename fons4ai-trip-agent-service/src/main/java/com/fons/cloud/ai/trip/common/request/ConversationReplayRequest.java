package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.ai.agent.model.request.AgentApprovalAction;
import com.fons.cloud.common.request.BaseRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;
import java.util.List;

/**
 * 一次中断的完整审批决定。恢复路由只从服务端票证读取，不接收客户端的checkpoint或子会话ID。
 * @author hongqy
 */
@Getter
@Setter
public class ConversationReplayRequest extends BaseRequest {

    /**
     * 登录用户ID， 控制层赋值
     */
    private String userId;

    /**
     * 会话ID，不能为空
     */
    @NotBlank(message = "会话Id不能为空")
    private String conversationId;

    /** 同一次Run收口产生的批次；同批卡片统一提交。 */
    @NotBlank(message = "审批批次不能为空")
    private String pauseId;

    /** 客户端展示时的框架版本，必须原样提交。 */
    @NotNull(message = "审批版本不能为空")
    @Positive(message = "审批版本必须为正数")
    private Long pauseVersion;

    /** 本次提交的稳定标识，重复点击及网络重试不能换标识重新执行。 */
    @NotBlank(message = "审批请求标识不能为空")
    @Size(max = 128, message = "审批请求标识不能超过128个字符")
    private String requestId;

    /** 必须包含本批全部审批消息，缺项、重复项或混入其他批次均整批拒绝。 */
    @Valid
    @NotEmpty(message = "必须提交本批全部审批决定")
    private List<@NotNull Decision> decisions;

    /** 一张审批卡片的决定，不承载原生恢复坐标。 */
    @Getter
    @Setter
    public static class Decision {
        /** 持久化审批消息ID，服务端按会话及批次核对归属。 */
        @NotBlank(message = "审批消息Id不能为空")
        private String messageId;

        /** 批准、编辑或拒绝；拒绝一项不取消本批其他节点。 */
        @NotNull(message = "审批行为不能为空")
        private AgentApprovalAction action;

        /** EDIT时提交完整的新工具参数，其他决定不接受参数覆盖。 */
        private Map<String, Object> params;
    }
}
