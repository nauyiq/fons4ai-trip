package com.fons.cloud.ai.trip.common.constants;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fons.cloud.ai.trip.common.exception.TripExceptions;

/**
 * 单项偏好状态；枚举名沿用既有 JSON 和存储值。
 * @author hongqy
 */
public enum PreferenceStatus {
    /**
     * 用户尚未确认该字段，仅用于查询缺省值，不持久化为已确认状态。
     */
    UNSET,
    /**
     * 已由可核验的用户确认保存，携带所属目录中的有效选项值。
     */
    ACTIVE,
    /**
     * 用户已明确清除，保留撤回时间和来源，防止旧记忆自动补回。
     */
    CLEARED;

    /**
     * 严格解析存储与接口中的状态编码，不接受枚举序号或未知状态。
     */
    @JsonCreator
    @JSONCreator
    public static PreferenceStatus fromCode(String code) {
        for (PreferenceStatus status : values()) {
            if (status.name().equals(code)) {
                return status;
            }
        }
        throw TripExceptions.validation(TripAgentResultCode.PREFERENCE_INVALID);
    }
}
