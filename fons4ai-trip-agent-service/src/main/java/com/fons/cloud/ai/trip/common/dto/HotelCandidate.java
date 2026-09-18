package com.fons.cloud.ai.trip.common.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Trip标准酒店搜索候选，关联本次入住日期和供应商当前能提供的报价选项。
 * 仅提供酒店最低起价时仍可作为搜索候选，但不能视为已取得具体房型的可预订报价。
 * 供应商未提供的可选字段为null；星级、早餐和价格口径均须有明确依据。
 *
 * @param candidateId           稳定候选标识，区分酒店、入住日期、入住人数和已知报价选项；补搜保持一致
 * @param source                供应商来源，必填，供应商酒店ID放入itemId
 * @param name                  酒店名称，必填
 * @param city                  酒店所在城市，未知时可从原搜索条件补齐
 * @param address               地址，未知为null
 * @param businessArea          商圈，未知为null
 * @param brand                 品牌，未知为null
 * @param starRating            明确的酒店星级，未知为null，不将高档型等分类自动转换成星数
 * @param classification        酒店星级或档次原文，未知为null
 * @param reviewScore           供应商点评评分，不做跨供应商归一化；未知为null
 * @param reviewSummary         点评摘要原文，未知为null
 * @param imageUrl              酒店图片地址，未知为null
 * @param roomType              房型，未知为null
 * @param roomAreaDescription   房间面积原文，未知为null
 * @param roomWindowDescription 窗户说明原文，未知为null
 * @param checkInDate           实际搜索的入住日期，必填，按酒店所在地日历解释
 * @param checkOutDate          实际搜索的离店日期，必填且晚于入住日期
 * @param nights                实际入住晚数，必须与日期一致且大于0
 * @param adultCount            实际搜索采用的成人数，必须大于0
 * @param childCount            实际搜索采用的儿童数，必须大于等于0
 * @param price                 标准报价，必填，保留起价标记及真实计价口径
 * @param distanceReference     距离对应的明确到访地点，未知为null
 * @param distanceKm            到distanceReference的距离，单位千米，未知为null，不默认0
 * @param distanceDescription   距离原文，未知为null
 * @param breakfastIncluded     是否含早餐，未知为null；不能仅以原文包含早餐二字判断
 * @param mealDescription       餐食说明原文，如无早餐，未知为null
 * @param cancelPolicy          取消政策原文，未知为null
 * @author hongqy
 */
public record HotelCandidate(String candidateId, CandidateSource source, String name, String city,
                             String address, String businessArea, String brand, Integer starRating,
                             String classification, BigDecimal reviewScore, String reviewSummary,
                             String imageUrl, String roomType, String roomAreaDescription,
                             String roomWindowDescription, LocalDate checkInDate, LocalDate checkOutDate,
                             int nights, int adultCount, int childCount, CandidatePrice price,
                             String distanceReference, BigDecimal distanceKm, String distanceDescription,
                             Boolean breakfastIncluded, String mealDescription, String cancelPolicy) {
}
