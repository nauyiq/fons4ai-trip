package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.ChatRequestTrace;
import com.fons.cloud.ai.trip.common.constants.TaskState;

/**
 * @author hongqy
 */
public interface ChatRequestTraceDomainService extends IService<ChatRequestTrace> {

    /** 仅更新状态列，避免状态收口覆盖已保存的分析结果和工具信息。 */
    boolean updateState(Long traceId, TaskState state);
}
