package com.fons.cloud.ai.trip.common.response;

/**
 * 当前用户常驻城市，baseCity 为空字符串时表示没有可用默认值。
 * 默认值仅用于用户未明确提供出发城市时，不覆盖用户本次指定的地点。
 * @author hongqy
 */
public record QueryUserBaseLocationResult(String userId, String baseCity) {
}
