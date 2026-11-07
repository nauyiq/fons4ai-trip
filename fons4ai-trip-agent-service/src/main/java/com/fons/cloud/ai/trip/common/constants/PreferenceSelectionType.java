package com.fons.cloud.ai.trip.common.constants;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fons.cloud.ai.trip.common.exception.TripExceptions;

/**
 * 固定目录值的选择方式；JSON 沿用原有 single / multi code。
 * @author hongqy
 */
public enum PreferenceSelectionType {
    /**
     * 值必须是所属目录中的单个选项 code。
     */
    SINGLE("single"),
    /**
     * 值必须是所属目录中选项 code 的非空列表。
     */
    MULTI("multi");

    /**
     * 对外选项目录中的选择方式标识。
     */
    private final String code;

    PreferenceSelectionType(String code) {
        this.code = code;
    }

    /**
     * JSON 只输出原有选择方式 code。
     */
    @JsonValue
    @JSONField(value = true)
    public String getCode() {
        return code;
    }

    /**
     * 严格解析既有选择方式 code，不接受枚举名或未支持值。
     *
     * @param code JSON 中的选择方式标识，不能为空
     * @return 匹配的选择方式
     */
    @JsonCreator
    @JSONCreator
    public static PreferenceSelectionType fromCode(String code) {
        for (PreferenceSelectionType type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        throw TripExceptions.validation(TripAgentResultCode.PREFERENCE_INVALID);
    }
}
