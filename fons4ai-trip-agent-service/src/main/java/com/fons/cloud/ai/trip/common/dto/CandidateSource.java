package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.constants.ItinerarySearchProvider;

/**
 * 搜索候选来源，仅承接追溯与后续供应商查询所需的标识，不携带原始响应或凭据。
 *
 * @param provider 搜索供应商，必填
 * @param itemId 供应商商品标识；未提供时为null，航班号、车次号不能冒充唯一商品ID
 * @param offerId 供应商报价选项标识，未提供时为null；不能据此假定报价可直接预订
 * @author hongqy
 */
public record CandidateSource(ItinerarySearchProvider provider, String itemId, String offerId) {
}
