package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * 目的地资讯主题及中文搜索关键词。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum DestinationNewsTopic {

    /**
     * 交通动态与管制资讯。
     */
    TRAFFIC("traffic", "交通"),
    /**
     * 活动、展览等资讯。
     */
    EVENT("event", "活动"),
    /**
     * 安全相关资讯。
     */
    SAFETY("safety", "安全"),
    /**
     * 航班相关资讯，不表示实时航班状态。
     */
    FLIGHT("flight", "航班"),
    /**
     * 酒店相关资讯，不表示可订库存。
     */
    HOTEL("hotel", "酒店"),
    /**
     * 政策法规相关资讯。
     */
    POLICY("policy", "政策"),
    /**
     * 综合资讯，仅按城市查询。
     */
    GENERAL("general", "");

    /**
     * 工具参数使用的主题编码。
     */
    private final String code;
    /**
     * 查询中文资讯使用的主题关键词，综合主题为空。
     */
    private final String queryKeyword;

    /**
     * 空白默认综合主题，忽略首尾空格和大小写；不支持的主题返回 null。
     */
    public static DestinationNewsTopic of(String code) {
        String normalizedCode = StringUtils.trimToEmpty(code);
        if (normalizedCode.isEmpty()) {
            return GENERAL;
        }
        for (DestinationNewsTopic topic : values()) {
            if (topic.code.equalsIgnoreCase(normalizedCode)) {
                return topic;
            }
        }
        return null;
    }
}
