package com.fons.cloud.ai.trip.common.constants;

import com.alibaba.fastjson2.annotation.JSONCreator;
import com.alibaba.fastjson2.annotation.JSONField;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;

import java.util.List;

/**
 * 七类固定长期偏好及目录约束；自然语言条件规则不强行压成无条件默认值。
 *
 * @author hongqy
 */
public enum PreferenceKey {
    /**
     * 默认交通方式，仅允许高铁或飞机。
     */
    TRANSPORT_MODE("transportMode", "出行方式", PreferenceSelectionType.SINGLE, PreferenceGroup.TRANSPORT,
            List.of(PreferenceChoice.RAIL, PreferenceChoice.AIR)),
    /**
     * 高铁席别，FIRST / BUSINESS 分别表示一等座 / 商务座。
     */
    TRAIN_SEAT_CLASS("trainSeatClass", "高铁席别", PreferenceSelectionType.SINGLE, PreferenceGroup.TRANSPORT,
            List.of(PreferenceChoice.TRAIN_SECOND, PreferenceChoice.TRAIN_FIRST, PreferenceChoice.TRAIN_BUSINESS)),
    /**
     * 飞机舱等，FIRST / BUSINESS 分别表示头等舱 / 商务舱。
     */
    FLIGHT_CABIN("flightCabin", "飞机舱等", PreferenceSelectionType.SINGLE, PreferenceGroup.TRANSPORT,
            List.of(PreferenceChoice.FLIGHT_ECONOMY, PreferenceChoice.FLIGHT_PREMIUM_ECONOMY,
                    PreferenceChoice.FLIGHT_BUSINESS, PreferenceChoice.FLIGHT_FIRST)),
    /**
     * 靠窗或靠过道的座位位置。
     */
    SEAT_POSITION("seatPosition", "座位位置", PreferenceSelectionType.SINGLE, PreferenceGroup.TRANSPORT,
            List.of(PreferenceChoice.WINDOW, PreferenceChoice.AISLE)),
    /**
     * 酒店星级，当前目录仅支持三星至五星。
     */
    HOTEL_STAR("hotelStar", "酒店星级", PreferenceSelectionType.SINGLE, PreferenceGroup.HOTEL,
            List.of(PreferenceChoice.HOTEL_THREE_STAR, PreferenceChoice.HOTEL_FOUR_STAR, PreferenceChoice.HOTEL_FIVE_STAR)),
    /**
     * 可多选的酒店特征，按此处目录顺序规范化保存。
     */
    HOTEL_FEATURES("hotelFeatures", "酒店特征", PreferenceSelectionType.MULTI, PreferenceGroup.HOTEL,
            List.of(PreferenceChoice.QUIET, PreferenceChoice.NON_SMOKING, PreferenceChoice.BREAKFAST)),
    /**
     * 酒店房型，当前目录支持大床房与双床房。
     */
    HOTEL_ROOM_TYPE("hotelRoomType", "酒店房型", PreferenceSelectionType.SINGLE, PreferenceGroup.HOTEL,
            List.of(PreferenceChoice.KING, PreferenceChoice.TWIN));

    /**
     * 请求、响应和存储沿用的字段 code。
     */
    private final String code;
    /**
     * 目录展示的中文字段名称。
     */
    private final String label;
    /**
     * 值为单个选项 code 或非空选项列表的约束。
     */
    private final PreferenceSelectionType selectionType;
    /**
     * 页面目录所属分组。
     */
    private final PreferenceGroup group;
    /**
     * 该字段唯一允许的选项集合，声明顺序也是规范化顺序。
     */
    private final List<PreferenceChoice> choices;

    PreferenceKey(String code, String label, PreferenceSelectionType selectionType, PreferenceGroup group,
                  List<PreferenceChoice> choices) {
        this.code = code;
        this.label = label;
        this.selectionType = selectionType;
        this.group = group;
        this.choices = List.copyOf(choices);
    }

    /**
     * JSON 只输出原有字段 code，不将元数据扩展成对象。
     */
    @JsonValue
    @JSONField(value = true)
    public String getCode() {
        return code;
    }

    /**
     * 返回字段展示名称。
     */
    public String getLabel() {
        return label;
    }

    /**
     * 返回单选或多选约束。
     */
    public PreferenceSelectionType getSelectionType() {
        return selectionType;
    }

    /**
     * 返回页面目录所属分组。
     */
    public PreferenceGroup getGroup() {
        return group;
    }

    /**
     * 返回只适用于本字段的不可变选项目录。
     */
    public List<PreferenceChoice> getChoices() {
        return choices;
    }

    /**
     * 严格解析既有字段 code，不接受枚举名或未支持值。
     *
     * @param code JSON 中的字段标识，不能为空
     * @return 匹配的固定偏好字段
     */
    @JsonCreator
    @JSONCreator
    public static PreferenceKey fromCode(String code) {
        for (PreferenceKey key : values()) {
            if (key.code.equals(code)) {
                return key;
            }
        }
        throw BusinessRuntimeException.of(TripAgentResultCode.PREFERENCE_INVALID);
    }
}
