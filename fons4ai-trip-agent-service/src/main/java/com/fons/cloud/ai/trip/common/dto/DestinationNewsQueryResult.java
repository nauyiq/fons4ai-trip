package com.fons.cloud.ai.trip.common.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单页目的地资讯检索结果；关键词匹配不保证资讯直接适用于该城市或当前行程。
 *
 * @param query 实际查询词，由工具层结合城市、主题组装
 * @param source 资讯查询服务来源
 * @param available 是否已配置服务凭据；false时未发起请求，不能据此判断没有相关资讯
 * @param news 本页资讯；available=true且为空表示本次检索未返回匹配资讯，不表示没有出行风险
 * @author hongqy
 */
public record DestinationNewsQueryResult(String query, String source, boolean available, List<NewsArticle> news) {

    /**
     * 新闻摘要，不包含供应商全文或原始响应。
     *
     * @param title 标题
     * @param description 摘要，可为空
     * @param link 原文链接，用于来源核实
     * @param publishedAt 发布时间，可为空；与publishedTimeZone一起解释，不转换为服务器时区
     * @param publishedTimeZone 供应商返回的发布时间时区，可为空，缺失时不猜测业务时区
     * @param sourceId 发布媒体标识，可为空
     * @param sourceName 发布媒体名称，可为空
     */
    public record NewsArticle(String title, String description, String link, LocalDateTime publishedAt,
                              String publishedTimeZone, String sourceId, String sourceName) {
    }
}
