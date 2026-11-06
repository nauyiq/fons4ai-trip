package com.fons.cloud.ai.trip.common.constants;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;

/** 偏好页面的固定分组；JSON 沿用原有小写分组 code。 */
public enum PreferenceGroup {
    /** 出行方式、席别、舱等及座位位置。 */
    TRANSPORT("transport", "交通偏好", "🚄"),
    /** 酒店星级、特征及房型。 */
    HOTEL("hotel", "酒店偏好", "🏨");

    /** 对外目录中的分组标识。 */
    private final String code;
    /** 页面展示的分组名称。 */
    private final String label;
    /** 页面展示的分组图标。 */
    private final String icon;

    PreferenceGroup(String code, String label, String icon) {
        this.code = code;
        this.label = label;
        this.icon = icon;
    }

    /** JSON 只输出原有分组 code，不将元数据扩展成对象。 */
    @JsonValue
    @JSONField(value = true)
    public String getCode() {
        return code;
    }

    /** 返回分组展示名称。 */
    public String getLabel() {
        return label;
    }

    /** 返回分组展示图标。 */
    public String getIcon() {
        return icon;
    }

    /**
     * 严格解析既有分组 code，不接受枚举名或未支持值。
     *
     * @param code JSON 中的分组标识，不能为空
     * @return 匹配的固定分组
     */
    @JsonCreator
    @JSONCreator
    public static PreferenceGroup fromCode(String code) {
        for (PreferenceGroup group : values()) {
            if (group.code.equals(code)) {
                return group;
            }
        }
        throw BusinessRuntimeException.of(TripAgentResultCode.PREFERENCE_INVALID);
    }
}
