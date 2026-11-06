package com.fons.cloud.ai.trip.common.constants;

import lombok.Getter;

/**
 * 固定偏好选项元数据；相同 code 的含义必须由 PreferenceKey 限定，不按 code 全局反序列化。
 * @author hongqy
 */
@Getter
public enum PreferenceChoice {
    /**
     * 优先高铁出行。
     */
    RAIL("RAIL", "高铁"),
    /**
     * 优先飞机出行。
     */
    AIR("AIR", "飞机"),
    /**
     * 高铁二等座。
     */
    TRAIN_SECOND("SECOND", "二等座"),
    /**
     * 高铁一等座，限高铁席别字段使用。
     */
    TRAIN_FIRST("FIRST", "一等座"),
    /**
     * 高铁商务座，限高铁席别字段使用。
     */
    TRAIN_BUSINESS("BUSINESS", "商务座"),
    /**
     * 飞机经济舱。
     */
    FLIGHT_ECONOMY("ECONOMY", "经济舱"),
    /**
     * 飞机超级经济舱。
     */
    FLIGHT_PREMIUM_ECONOMY("PREMIUM_ECONOMY", "超经"),
    /**
     * 飞机商务舱，限飞机舱等字段使用。
     */
    FLIGHT_BUSINESS("BUSINESS", "商务舱"),
    /**
     * 飞机头等舱，限飞机舱等字段使用。
     */
    FLIGHT_FIRST("FIRST", "头等舱"),
    /**
     * 靠窗座位。
     */
    WINDOW("WINDOW", "靠窗"),
    /**
     * 靠过道座位。
     */
    AISLE("AISLE", "靠过道"),
    /**
     * 三星级酒店。
     */
    HOTEL_THREE_STAR("THREE", "三星"),
    /**
     * 四星级酒店。
     */
    HOTEL_FOUR_STAR("FOUR", "四星"),
    /**
     * 五星级酒店。
     */
    HOTEL_FIVE_STAR("FIVE", "五星"),
    /**
     * 安静的酒店环境。
     */
    QUIET("QUIET", "安静"),
    /**
     * 无烟酒店或客房。
     */
    NON_SMOKING("NON_SMOKING", "无烟"),
    /**
     * 提供早餐。
     */
    BREAKFAST("BREAKFAST", "早餐"),
    /**
     * 大床房。
     */
    KING("KING", "大床房"),
    /**
     * 双床房。
     */
    TWIN("TWIN", "双床房");

    /**
     * 请求和存储沿用的选项 code，需结合所属字段解释。
     */
    private final String code;
    /**
     * 页面与工具目录展示的中文标签。
     */
    private final String label;

    PreferenceChoice(String code, String label) {
        this.code = code;
        this.label = label;
    }

}
