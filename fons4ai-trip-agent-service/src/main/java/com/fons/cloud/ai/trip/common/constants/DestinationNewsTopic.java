package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

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
    TRAFFIC("traffic", List.of("交通", "地铁", "公交", "高铁", "铁路", "道路", "限行")),
    /**
     * 活动、展览等资讯。
     */
    EVENT("event", List.of("活动", "展会", "展览", "会议", "赛事")),
    /**
     * 安全相关资讯。
     */
    SAFETY("safety", List.of("安全", "预警", "事故", "灾害", "治安")),
    /**
     * 航班相关资讯，不表示实时航班状态。
     */
    FLIGHT("flight", List.of("航班", "机场", "航空")),
    /**
     * 酒店相关资讯，不表示可订库存。
     */
    HOTEL("hotel", List.of("酒店", "宾馆", "住宿")),
    /**
     * 政策法规相关资讯。
     */
    POLICY("policy", List.of("政策", "规定", "入境", "签证")),
    /**
     * 综合资讯，仅按城市查询。
     */
    GENERAL("general", List.of());

    /**
     * 工具参数使用的主题编码。
     */
    private final String code;
    /**
     * 查询和本地候选门槛共用的固定关键词，不接受模型扩展查询语法；综合主题为空列表。
     */
    private final List<String> queryKeywords;

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
