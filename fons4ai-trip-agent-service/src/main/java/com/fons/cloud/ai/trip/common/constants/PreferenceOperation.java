package com.fons.cloud.ai.trip.common.constants;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;

/** 本次偏好修改动作；与查询结果中的偏好状态分别表达。 */
public enum PreferenceOperation {
    /** 设置合法目录值，成功写入后状态为 ACTIVE。 */
    SET(PreferenceStatus.ACTIVE),
    /** 清除保存值，成功写入后保留 CLEARED 状态与确认来源。 */
    CLEAR(PreferenceStatus.CLEARED);

    /** 操作成功写入后对应的偏好状态，不在数据对象或应用服务中重复映射。 */
    private final PreferenceStatus resultStatus;

    PreferenceOperation(PreferenceStatus resultStatus) {
        this.resultStatus = resultStatus;
    }

    /** 返回成功保存后的状态；不表示该操作已经执行或通过校验。 */
    public PreferenceStatus getResultStatus() {
        return resultStatus;
    }

    /** 严格接受现有操作编码，避免 JSON 数字被按枚举序号解释为修改动作。 */
    @JsonCreator
    @JSONCreator
    public static PreferenceOperation fromCode(String code) {
        for (PreferenceOperation operation : values()) {
            if (operation.name().equals(code)) {
                return operation;
            }
        }
        throw BusinessRuntimeException.of(TripAgentResultCode.PREFERENCE_INVALID);
    }
}
