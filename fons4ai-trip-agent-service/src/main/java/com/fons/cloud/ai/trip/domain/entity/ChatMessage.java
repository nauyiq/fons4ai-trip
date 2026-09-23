package com.fons.cloud.ai.trip.domain.entity;

import cn.hutool.core.util.IdUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopInfo;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.agent.model.response.AgentCompleteInfo;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.agent.core.TripAgent;
import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 对话消息
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_message")
public class ChatMessage extends BaseEntity {

    /**
     * 消息ID
     */
    @TableId
    private String messageId;

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 所属agent请求的id
     */
    private String runId;

    /**
     * 角色：user/agent/system
     */
    private ChatRole role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 构造给用户的思考内容, 并非表示LLM的思考内容
     */
    private String thinking;

    /**
     * 消息类型
     */
    private ChatMessageContentType type;

    /**
     * Agent名称（role=agent 时）
     */
    private String agentName;

    /**
     * 扩展信息（进度快照/推荐问题等）
     */
    private String extra;

    /**
     * 用户反馈：LIKE 点赞 / DISLIKE 点踩 / NULL 未反馈
     */
    private String feedback;

    /**
     * 反馈时间
     */
    private Date feedbackAt;

    /**
     * 逻辑删除标志
     */
    @TableLogic
    private boolean deleted;

    public static ChatMessage create(String conversationId, String runId, ChatRole role, ChatMessageRequest request) {
        return ChatMessage.builder()
                .runId(runId)
                .messageId(generateMessageId())
                .conversationId(conversationId)
                .content(request.getContent())
                .type(request.getMessageType())
                .role(role)
                .deleted(false)
                .build();
    }

    public static ChatMessage replay(String agentName, AgentRunResult result) {
        ChatMessageBuilder builder = ChatMessage.builder()
                .runId(result.getRunId())
                .messageId(generateMessageId())
                .conversationId(result.getConversationId())
                .agentName(agentName)
                .role(ChatRole.AGENT)
                // TODO AgentScope2.xx 是支持多模态输出 框架还未接入 这里先写死 后续进行优化
                .type(ChatMessageContentType.TEXT)
                .deleted(false);

        AgentCompleteInfo completeInfo = result.getCompleteInfo();
        List<HumanInTheLoopInfo> humanInTheLoopInfos = result.getHumanInTheLoopInfos();

        List<HumanInTheLoopInfo> approvals = humanInTheLoopInfos.stream()
                .filter(info -> info.getKind() == HumanInTheLoopKind.APPROVAL)
                .toList();
        List<HumanInTheLoopInfo> inputRequests = humanInTheLoopInfos.stream()
                .filter(info -> info.getKind() == HumanInTheLoopKind.INPUT_REQUIRED)
                .toList();

        if (result.getState() == AgentRunState.WAITING_APPROVAL && CollectionUtils.isNotEmpty(approvals)) {
            // 审批暂停才使用审批消息角色；不能把正常结束的输入请求当作审批恢复。
            builder.role(ChatRole.AGENT_HITL);
            builder.content(JSON.toJSONString(approvals));
        }
        if (completeInfo != null) {
            if (result.getState() != AgentRunState.WAITING_APPROVAL || approvals.isEmpty()) {
                builder.content(completeInfo.getFinalAnswer());
            }
            builder.thinking(completeInfo.getThinking());
        }
        if (result.getState() == AgentRunState.COMPLETED && !inputRequests.isEmpty()) {
            // 正常结束的补充信息请求保持普通Agent正文，并在历史消息中保留结构化交互。
            builder.extra(JSON.toJSONString(Map.of("interactions", inputRequests)));
        }

        return builder.build();
    }

    public static String generateMessageId() {
        return "msg_" + IdUtil.fastSimpleUUID();
    }




}
