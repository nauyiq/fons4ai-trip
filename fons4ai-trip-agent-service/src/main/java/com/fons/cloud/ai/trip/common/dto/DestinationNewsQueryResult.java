package com.fons.cloud.ai.trip.common.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 单页目的地资讯检索结果；关键词匹配不保证资讯直接适用于该城市或当前行程。
 *
 * @param query 实际供应商查询表达式，由查询应用服务结合城市、主题组装
 * @param source 资讯查询服务来源
 * @param available 是否已配置服务凭据；false时未发起请求，不能据此判断没有相关资讯
 * @param retrievedCount 供应商本页原始返回数，不是全网命中数；未配置为0，需结合available区分未查询和真空页
 * @param news Client内部为本页解析列表，应用服务输出为经过城市/主题门槛及基础去重的候选；为空不表示没有出行风险
 * @author hongqy
 */
public record DestinationNewsQueryResult(String query, String source, boolean available, int retrievedCount,
                                         List<NewsArticle> news) {

    /** 固定本次查询快照，避免调用方在筛选或读取时修改结果列表。 */
    public DestinationNewsQueryResult {
        news = List.copyOf(news);
    }

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
