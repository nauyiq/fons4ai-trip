package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.ai.trip.common.constants.FlightSearchMode;
import com.fons.cloud.ai.trip.common.dto.SearchPriceRange;
import com.fons.cloud.ai.trip.common.dto.SearchTimeRange;
import lombok.Builder;

import java.time.LocalDate;

/**
 * Trip机票搜索契约，第一阶段为国内单程搜索，往返分别调用。
 * 不包含供应商字段、个人身份或服务凭据；参数校验及供应商适配由客户端完成。
 *
 * @param origin 出发城市，必填，首尾空格由客户端去除
 * @param destination 到达城市，必填，不得与出发城市相同
 * @param departureDate 明确出发日期，按出发地当地日历解释
 * @param mode 搜索方式，null表示优先低价
 * @param departureTimeRange 可选出发时间范围，仅TIME_RANGE方式使用
 * @param arrivalTimeRange 可选到达时间范围，仅TIME_RANGE方式使用
 * @param priceRange 可选价格范围，仅PRICE_RANGE方式使用
 * @param page 分页条件，null表示首页；翻页时保留相同搜索条件
 * @author hongqy
 */
@Builder
public record FlightSearchRequest(String origin, String destination, LocalDate departureDate,
                                  FlightSearchMode mode, SearchTimeRange departureTimeRange,
                                  SearchTimeRange arrivalTimeRange, SearchPriceRange priceRange,
                                  SearchPageRequest page) {
}
