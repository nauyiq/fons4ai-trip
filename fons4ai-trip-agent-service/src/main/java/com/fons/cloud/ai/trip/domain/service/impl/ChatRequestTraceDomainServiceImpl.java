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
}
