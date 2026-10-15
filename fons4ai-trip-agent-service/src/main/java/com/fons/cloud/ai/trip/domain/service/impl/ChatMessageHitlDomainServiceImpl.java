package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.ChatMessageHitlStatus;
import com.fons.cloud.ai.trip.domain.entity.ChatMessageHitl;
import com.fons.cloud.ai.trip.domain.mapper.ChatMessageHitlMapper;
import com.fons.cloud.ai.trip.domain.service.ChatMessageHitlDomainService;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import org.springframework.stereotype.Service;
import java.util.List;

/**
 * 对话消息HITL扩展领域服务实现
 *
 * @author hongqy
 */
@Service
public class ChatMessageHitlDomainServiceImpl extends ServiceImpl<ChatMessageHitlMapper, ChatMessageHitl> implements ChatMessageHitlDomainService {

    @Override
    public boolean hasUnresolvedApproval(String conversationId) {
        return count(Wrappers.lambdaQuery(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getConversationId, conversationId)
                .in(ChatMessageHitl::getStatus, ChatMessageHitlStatus.PREPARED,
                        ChatMessageHitlStatus.PENDING, ChatMessageHitlStatus.RESUMING, ChatMessageHitlStatus.FAILED)) > 0;
    }

    @Override
    public List<ChatMessageHitl> findBatch(String conversationId, String pauseId, Long pauseVersion, String pipelineRunId) {
        return list(Wrappers.lambdaQuery(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getConversationId, conversationId)
                .eq(ChatMessageHitl::getPauseId, pauseId)
                .eq(ChatMessageHitl::getPauseVersion, pauseVersion)
                .eq(ChatMessageHitl::getPipelineRunId, pipelineRunId)
                .orderByAsc(ChatMessageHitl::getMessageId));
    }

    @Override
    public int markBatchPending(String conversationId, String pauseId, Long pauseVersion, String pipelineRunId) {
        return baseMapper.update(null, Wrappers.lambdaUpdate(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getConversationId, conversationId)
                .eq(ChatMessageHitl::getPauseId, pauseId)
                .eq(ChatMessageHitl::getPauseVersion, pauseVersion)
                .eq(ChatMessageHitl::getPipelineRunId, pipelineRunId)
                .eq(ChatMessageHitl::getStatus, ChatMessageHitlStatus.PREPARED)
                .set(ChatMessageHitl::getStatus, ChatMessageHitlStatus.PENDING));
    }

    @Override
    public List<ChatMessageHitl> findBatch(String conversationId, String pauseId) {
        return list(Wrappers.lambdaQuery(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getConversationId, conversationId)
                .eq(ChatMessageHitl::getPauseId, pauseId)
                // 固定批次顺序，校验、审计和条件占用使用同一个消息集合。
                .orderByAsc(ChatMessageHitl::getMessageId));
    }

    @Override
    public boolean hasResumeRequest(String conversationId, String requestId) {
        return count(Wrappers.lambdaQuery(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getConversationId, conversationId)
                .eq(ChatMessageHitl::getResumeRequestId, requestId)) > 0;
    }

    @Override
    public int claimBatch(String conversationId, String pauseId, Long pauseVersion, List<String> messageIds,
                          String requestId, String resumeRunId) {
        return baseMapper.update(null, Wrappers.lambdaUpdate(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getConversationId, conversationId)
                .eq(ChatMessageHitl::getPauseId, pauseId)
                .eq(ChatMessageHitl::getPauseVersion, pauseVersion)
                .in(ChatMessageHitl::getMessageId, messageIds)
                .eq(ChatMessageHitl::getStatus, ChatMessageHitlStatus.PENDING)
                .isNull(ChatMessageHitl::getResumeRequestId)
                .isNull(ChatMessageHitl::getResumeRunId)
                .set(ChatMessageHitl::getStatus, ChatMessageHitlStatus.RESUMING)
                .set(ChatMessageHitl::getResumeRequestId, requestId)
                .set(ChatMessageHitl::getResumeRunId, resumeRunId));
    }

    @Override
    public int finishResume(String conversationId, String pauseId, String requestId, String resumeRunId,
                            ChatMessageHitlStatus status) {
        if (status != ChatMessageHitlStatus.PENDING && status != ChatMessageHitlStatus.CONSUMED
                && status != ChatMessageHitlStatus.FAILED) {
            throw SystemIntervalException.of("恢复票证只能释放、消费或标记失败");
        }
        var update = Wrappers.lambdaUpdate(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getConversationId, conversationId)
                .eq(ChatMessageHitl::getPauseId, pauseId)
                .eq(ChatMessageHitl::getResumeRequestId, requestId)
                .eq(ChatMessageHitl::getResumeRunId, resumeRunId)
                .eq(ChatMessageHitl::getStatus, ChatMessageHitlStatus.RESUMING)
                .set(ChatMessageHitl::getStatus, status);
        if (status == ChatMessageHitlStatus.PENDING) {
            // 只有明确未启动的Run可以释放占用，允许原提交修正或安全重试。
            update.set(ChatMessageHitl::getResumeRequestId, null).set(ChatMessageHitl::getResumeRunId, null);
        }
        return baseMapper.update(null, update);
    }

    @Override
    public boolean changeStatus(String messageId, ChatMessageHitlStatus expectedStatus, ChatMessageHitlStatus newStatus) {
        return update(Wrappers.lambdaUpdate(ChatMessageHitl.class)
                .eq(ChatMessageHitl::getMessageId, messageId)
                .eq(ChatMessageHitl::getStatus, expectedStatus)
                .set(ChatMessageHitl::getStatus, newStatus));
    }
}
