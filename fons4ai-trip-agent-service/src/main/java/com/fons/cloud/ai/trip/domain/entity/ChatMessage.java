package com.fons.cloud.ai.trip.domain.entity;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopInfo;
import com.fons.cloud.ai.agent.model.hitl.HumanInTheLoopKind;
import com.fons.cloud.ai.agent.model.response.AgentCompleteInfo;
import com.fons.cloud.ai.agent.model.response.AgentMediaInfo;
import com.fons.cloud.ai.agent.model.response.AgentRunResult;
import com.fons.cloud.ai.agent.model.runtime.AgentRunState;
import com.fons.cloud.ai.trip.common.constants.ChatMessageContentType;
import com.fons.cloud.ai.trip.common.constants.ChatRole;
import com.fons.cloud.ai.trip.common.request.ChatMessageRequest;
import com.fons.cloud.ai.trip.infrastructure.util.IdGenerator;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;
import org.apache.commons.collections4.CollectionUtils;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
    @TableId(type = IdType.INPUT)
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

    /**
     * 创建一条用户消息
     *
     * @param conversationId
     * @param runId
     * @param role
     * @param request
     * @return
     */
    public static ChatMessage createUser(String conversationId, String runId, ChatRole role, ChatMessageRequest request) {
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

    public static List<ChatMessage> createAgent(String agentName, AgentRunResult result) {
        List<HumanInTheLoopInfo> humanInTheLoopInfos = result.getHumanInTheLoopInfos();
        if (CollectionUtils.isNotEmpty(humanInTheLoopInfos)) {
            // 创建HITL的消息 从业务逻辑来看只会有一条， 但是设计看考虑到多个子Agent同时返回HITL消息时 业务上要支持多个HITL的审批
            List<ChatMessage> hitlMessages = new ArrayList<>();
            List<HumanInTheLoopInfo> inputRequests = humanInTheLoopInfos.stream()
                    .filter(info -> info.getKind() == HumanInTheLoopKind.INPUT_REQUIRED)
                    .toList();
            if (CollectionUtils.isNotEmpty(inputRequests)) {
                hitlMessages.addAll(createInputRequired(result));
            }

            // 这里的信息补充消息正常业务也只会有一条， 同应考虑到多AGENT都要进行信息补充。
            List<HumanInTheLoopInfo> approvals = humanInTheLoopInfos.stream()
                    .filter(info -> info.getKind() == HumanInTheLoopKind.APPROVAL)
                    .toList();
            if (CollectionUtils.isNotEmpty(approvals)) {
                hitlMessages.addAll(createApproval(result));
            }
            return hitlMessages;
        } else {
            // 创建最终Agent回复的消息
            AgentCompleteInfo completeInfo = result.getCompleteInfo();
            Assert.notNull(completeInfo, () -> SystemIntervalException.of("Agent最终回复内容为空"));
            List<ChatMessage> messages = new ArrayList<>();
            ChatMessage finalAnswer = ChatMessage.builder()
                    .runId(result.getRunId())
                    .messageId(generateMessageId())
                    .content(completeInfo.getFinalAnswer())
                    .thinking(completeInfo.getThinking())
                    .conversationId(result.getConversationId())
                    .agentName(agentName)
                    .role(ChatRole.AGENT)
                    .type(ChatMessageContentType.TEXT)
                    .deleted(false).build();
            messages.add(finalAnswer);

            // 创建多媒体消息
            List<AgentMediaInfo> media = completeInfo.getMedia();
            if (CollectionUtils.isNotEmpty(media)) {
                List<ChatMessage> mediaMessages = media.stream().map(mediaContent -> ChatMessage.builder()
                        .runId(result.getRunId())
                        .messageId(generateMessageId())
                        .content(mediaContent.getUri())
                        .conversationId(result.getConversationId())
                        .agentName(agentName)
                        .role(ChatRole.AGENT)
                        // 暂时支持图片
                        .type(ChatMessageContentType.IMAGE)
                        .deleted(false).build()).toList();
                messages.addAll(mediaMessages);
            }
            return messages;
        }


    }



    /**
     * 根据Agent运行结果创建多条信息补充消息
     * <pre>
     *     审批消息只处理类型为INPUT_REQUIRED
     * </pre>
     * @param result
     * @return
     */
    public static List<ChatMessage> createInputRequired(AgentRunResult result) {
        List<HumanInTheLoopInfo> approvalInfos = result.getHumanInTheLoopInfos().stream().filter(hitl -> hitl.getKind() == HumanInTheLoopKind.INPUT_REQUIRED).toList();
        Assert.notEmpty(approvalInfos, () -> SystemIntervalException.of("未发现信息补充消息， 无法创建Agent信息补充消息"));

        AgentCompleteInfo completeInfo = result.getCompleteInfo();
        return approvalInfos.stream().map(requiredInfo -> ChatMessage.builder()
                .runId(result.getRunId())
                .messageId(generateMessageId())
                .conversationId(result.getConversationId())
                .agentName(requiredInfo.getSourceAgent())
                // 信息补充消息默认角色是AGENT, 因为不需要中断恢复
                .role(ChatRole.AGENT)
                .content(requiredInfo.getQuestion())
                .extra(JSON.toJSONString(requiredInfo.getData()))
                .thinking(completeInfo == null ? "" : completeInfo.getThinking())
                .type(ChatMessageContentType.TEXT)
                .deleted(false)
                .build()).toList();
    }

    /**
     * 根据Agent运行结果创建多条审批消息
     * <pre>
     *     审批消息只处理类型为APPROVAL
     * </pre>
     * @param result
     * @return
     */
    public static List<ChatMessage> createApproval(AgentRunResult result) {
        List<HumanInTheLoopInfo> approvalInfos = result.getHumanInTheLoopInfos().stream().filter(hitl -> hitl.getKind() == HumanInTheLoopKind.APPROVAL).toList();
        Assert.notEmpty(approvalInfos, () -> SystemIntervalException.of("未发现审批消息， 无法创建Agent审批消息"));

        AgentCompleteInfo completeInfo = result.getCompleteInfo();
        return approvalInfos.stream().map(hitl -> ChatMessage.builder()
                .runId(result.getRunId())
                .messageId(generateMessageId())
                .conversationId(result.getConversationId())
                .agentName(hitl.getSourceAgent())
                .role(ChatRole.AGENT_HITL)
                .content(hitl.getId())
                .extra(JSON.toJSONString(hitl.getData()))
                .thinking(completeInfo == null ? "" : completeInfo.getThinking())
                .type(ChatMessageContentType.TEXT)
                .deleted(false).build()).toList();
    }

    public static String generateMessageId() {
        return IdGenerator.next(IdGenerator.Prefix.CONVERSATION);
    }


}
