package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.ai.trip.common.dto.SearchPriceRange;
import lombok.Builder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Trip酒店搜索契约，第一阶段搜索单间住宿，必须明确入住和离店日期。
 * 儿童年龄列表保存为只读快照；身份与候选归属由应用服务处理。
 *
 * @param city 搜索城市，必填
 * @param checkInDate 入住日期，必填，按酒店所在地日历解释
 * @param checkOutDate 离店日期，必填，必须晚于入住日期
 * @param keyword 可选关键词，可包含多个用空格分隔的关键词
 * @param destinationLocation 可选到访地点名称，用于搜索附近酒店；不是另一个城市
 * @param priceRange 可选价格范围，供应商具体过滤口径由客户端承接
 * @param adultCount 成人数，null表示2；必须大于0
 * @param childAges 儿童年龄列表，null或空列表表示没有儿童；儿童数由列表长度计算
 * @param page 分页条件，null表示首页；翻页时保留全部原条件
 * @author hongqy
 */
@Builder
public record HotelSearchRequest(String city, LocalDate checkInDate, LocalDate checkOutDate,
                                 String keyword, String destinationLocation, SearchPriceRange priceRange,
                                 Integer adultCount, List<Integer> childAges, SearchPageRequest page) {

    public HotelSearchRequest {
        if (childAges != null) {
            childAges = Collections.unmodifiableList(new ArrayList<>(childAges));
        }
    }
}
