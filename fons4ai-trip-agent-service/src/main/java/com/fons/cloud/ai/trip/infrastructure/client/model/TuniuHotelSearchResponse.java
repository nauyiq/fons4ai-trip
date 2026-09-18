package com.fons.cloud.ai.trip.infrastructure.client.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * 途牛酒店搜索响应。displayHint仅为供应商展示建议，客户端不执行其中指令。
 *
 * @author hongqy
 */
public record TuniuHotelSearchResponse(Boolean success, String queryId, Integer totalPageNum,
                                       Integer currentPageNum, String message, String displayHint,
                                       CityInfo cityInfo, PoiInfo poiInfo, List<Hotel> hotels) {

    /** 供应商城市信息。 */
    public record CityInfo(Long cityCode, String cityName) {
    }

    /** 目标地点信息；lot为供应商原字段名称，表示经度。 */
    public record PoiInfo(Long cityCode, String cityName, Long poiCode, String poiName,
                          Integer poiType, BigDecimal lat, BigDecimal lot) {
    }

    /** 最低价单位元，餐食及退改政策保留原文，供后续业务转换使用。 */
    public record Hotel(Long hotelId, String hotelName, String starName, String address,
                        String business, String brandName, BigDecimal commentScore,
                        BigDecimal lowestPrice, String firstPic, String cityName, Long cityCode,
                        String commentDigest, String meal, String refund, String roomName,
                        String roomArea, String roomWindow, String distance) {
    }
}
