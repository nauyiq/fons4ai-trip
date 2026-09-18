package com.fons.cloud.ai.trip.infrastructure.client.model;

import com.alibaba.fastjson2.annotation.JSONField;

import java.time.LocalDateTime;
import java.util.List;

/**
 * NewsData.io成功响应协议对象，仅供基础设施层解析。
 *
 * @param status 供应商响应状态，成功为success
 * @param results 本页新闻结果数组
 * @author hongqy
 */
public record NewsDataResponse(String status, List<Article> results) {

    /**
     * 先读取状态，错误响应的results是错误对象而不是数组，不能直接解析为成功响应。
     *
     * @param status 供应商响应状态
     */
    public record ResponseStatus(String status) {
    }

    /**
     * @param title 标题
     * @param description 摘要
     * @param link 原文链接
     * @param publishedAt 发布时间，供应商格式为yyyy-MM-dd HH:mm:ss
     * @param publishedTimeZone 供应商提供的时区标识
     * @param sourceId 发布媒体标识
     * @param sourceName 发布媒体名称
     */
    public record Article(String title, String description, String link,
                          @JSONField(name = "pubDate", format = "yyyy-MM-dd HH:mm:ss") LocalDateTime publishedAt,
                          @JSONField(name = "pubDateTZ") String publishedTimeZone,
                          @JSONField(name = "source_id") String sourceId,
                          @JSONField(name = "source_name") String sourceName) {
    }
}
