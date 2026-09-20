package com.fons.cloud.ai.trip.infrastructure.client.model.tuniu;

import java.math.BigDecimal;
import java.util.List;

/**
 * 途牛酒店详情响应。preBookParam为短期下单参数，本响应只在客户端边界接收，
 * 不进入Trip长期候选；实际下单前应重新查询详情并验价。
 *
 * @author hongqy
 */
public record TuniuHotelDetailResponse(String displayHint, Long hotelId, String hotelName,
                                       String hotelNameEn, String starName, String firstPic,
                                       String address, String cityName, Long cityCode,
                                       String business, String brandName, BigDecimal commentScore,
                                       String commentDigest, Policies policies, Reviews reviews,
                                       List<RoomType> roomTypes) {

    /** 酒店入住、退房及通用取消政策。 */
    public record Policies(String checkInTime, String checkOutTime, String cancelPolicy) {
    }

    /** 酒店点评汇总。 */
    public record Reviews(BigDecimal score, Integer count) {
    }

    /** 一个实际房型及其多个报价方案。 */
    public record RoomType(String roomTypeId, String roomTypeName, String bedType,
                           Integer maxOccupancy, BigDecimal roomSize, String floor,
                           List<String> images, List<RatePlan> ratePlans) {
    }

    /** 房型报价方案；rmbPrices为人民币房价，count为当前剩余库存。 */
    public record RatePlan(String ratePlanName, String vendorRatePlanId, String rmbPrices,
                           String preBookParam, String mealText, String cancelDesc,
                           Integer count) {
    }
}
