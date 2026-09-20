package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.ai.trip.common.constants.TrainSearchSort;
import com.fons.cloud.ai.trip.common.dto.SearchTimeRange;
import lombok.Builder;

import java.time.LocalDate;

/**
 * Trip火车票单程搜索契约。不传供应商排序数字，也不传供应商席别字段名。
 * 翻页时仍保留原条件，客户端根据供应商协议发送必要的参数。
 *
 * @param origin 出发城市，必填
 * @param destination 到达城市，必填，不得与出发城市相同
 * @param departureDate 明确出发日期，必填
 * @param sort 排序方式，null表示价格升序
 * @param departureTimeRange 可选出发时间范围
 * @param arrivalTimeRange 可选到达时间范围
 * @param page 分页条件，null表示首页
 * @author hongqy
 */
@Builder
public record TrainSearchRequest(String origin, String destination, LocalDate departureDate,
                                 TrainSearchSort sort, SearchTimeRange departureTimeRange,
                                 SearchTimeRange arrivalTimeRange, SearchPageRequest page) {
}
