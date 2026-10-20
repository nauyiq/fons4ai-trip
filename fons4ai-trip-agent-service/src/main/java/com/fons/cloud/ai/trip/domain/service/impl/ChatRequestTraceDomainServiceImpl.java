package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.TaskState;
import com.fons.cloud.ai.trip.domain.entity.ChatRequestTrace;
import com.fons.cloud.ai.trip.domain.mapper.ChatRequestTraceMapper;
import com.fons.cloud.ai.trip.domain.service.ChatRequestTraceDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class ChatRequestTraceDomainServiceImpl extends ServiceImpl<ChatRequestTraceMapper, ChatRequestTrace> implements ChatRequestTraceDomainService {

    @Override
    public boolean updateState(String runId, TaskState state) {
        return update(Wrappers.lambdaUpdate(ChatRequestTrace.class)
                .eq(ChatRequestTrace::getRunId, runId)
                .set(ChatRequestTrace::getState, state));
    }

    @Override
    public boolean removeByConversationId(String conversationId) {
        update(Wrappers.lambdaUpdate(ChatRequestTrace.class)
                .eq(ChatRequestTrace::getConversationId, conversationId)
                .set(ChatRequestTrace::getDeleted, true));
        // 没有Trace的会话也允许删除，不能因影响行数为0回滚整个清理事务。
        return true;
    }

    @Override
    public boolean updateExecutionSummary(ChatRequestTrace trace) {
        return update(Wrappers.lambdaUpdate(ChatRequestTrace.class)
                .eq(ChatRequestTrace::getRunId, trace.getRunId())
                .eq(ChatRequestTrace::getConversationId, trace.getConversationId())
                .set(ChatRequestTrace::getAnalysisContent, trace.getAnalysisContent())
                .set(ChatRequestTrace::getUsedTools, trace.getUsedTools())
                .set(ChatRequestTrace::getUsedToken, trace.getUsedToken())
                .set(ChatRequestTrace::getExecutionSummary, trace.getExecutionSummary()));
    }
}
