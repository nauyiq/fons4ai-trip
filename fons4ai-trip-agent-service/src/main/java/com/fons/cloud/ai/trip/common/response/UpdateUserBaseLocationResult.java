package com.fons.cloud.ai.trip.common.response;

/**
 * 当前用户常驻城市写入结果。
 * @param created 是否新建用户档案
 * @author hongqy
 */
public record UpdateUserBaseLocationResult(String userId, String baseCity, boolean created) {
}
