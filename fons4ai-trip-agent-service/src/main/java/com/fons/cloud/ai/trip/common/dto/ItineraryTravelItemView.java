package com.fons.cloud.ai.trip.common.dto;

/**
 * 行程页面中一个交通或住宿环节的展示数据。
 * 文本由可信规划快照生成，缺失信息明确显示为未提供。
 *
 * @author hongqy
 */
public record ItineraryTravelItemView(String title, String headline, String location,
                                      String schedule, String details, String price) {
}
