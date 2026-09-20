package com.fons.cloud.ai.trip.common.request;

import lombok.Builder;

/**
 * Trip搜索分页条件。原搜索条件在翻页时仍由Flight/Train/HotelSearchRequest完整提供，
 * 客户端自行决定哪些条件发送给供应商，不依赖客户端内存保存上次请求。
 *
 * @param pageNumber 页码，从1开始；null表示首页
 * @param continuationToken 原搜索返回的续查凭据，首页为空；是否必填由客户端按协议校验。
 *                          仅可用于原供应商、原搜索条件，不是候选池ID或存储Key，不含API Key
 * @author hongqy
 */
@Builder
public record SearchPageRequest(Integer pageNumber, String continuationToken) {
}
