package com.fons.cloud.ai.trip.common.dto;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.ResultCode;

/**
 * 候选数据的可信归属，由工具或应用入口从运行上下文构造，不允许模型指定。
 * 当前项目按用户和会话隔离，不使用缺失身份的默认候选池。
 *
 * @param userId 当前用户ID，必填
 * @param conversationId 当前业务会话ID，必填，使用根会话而不是子Agent句柄
 * @author hongqy
 */
public record CandidateOwner(String userId, String conversationId) {

    public CandidateOwner {
        Assert.isTrue(userId != null && !userId.isBlank() && conversationId != null && !conversationId.isBlank(),
                () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "候选归属的用户和会话不能为空"));
        userId = userId.trim();
        conversationId = conversationId.trim();
    }
}
