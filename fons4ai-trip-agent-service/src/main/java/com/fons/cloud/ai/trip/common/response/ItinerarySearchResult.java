package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItinerarySearchProvider;
import com.fons.cloud.ai.trip.common.dto.SearchPagination;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 搜索客户端统一返回契约，候选已经转换成Trip结构，但尚未保存到业务候选池。
 * 不复用供应商successCode、success、data或hotels字段，不包含供应商展示指令及原始响应。
 * 调用、业务状态或协议解析失败抛出框架异常，不包装成成功空列表；工具层后续统一封装R。
 * 此对象不承担候选归属、候选池ID或数据库/Redis持久化状态。
 *
 * @param provider 实际搜索供应商，必填
 * @param searchedAt 搜索结果获取时间，包含UTC偏移，必填
 * @param candidates 当前页转换后的候选，必填；无匹配时为空列表。
 *                   火车席别拆分后，候选数量可能超过供应商车次数
 * @param pagination 本次分页信息，必填
 * @param discardedItemCount 无法转换而舍弃的供应商原始条目数，不是售罄或偏好排除的数量；
 *                           全部原始条目转换失败不能冒充正常无匹配，客户端应抛出异常
 * @param warnings 数据不完整、价格仅为起价或部分条目无法转换等提醒，必填，无提醒时为空列表
 * @param <T> TransportCandidate或HotelCandidate，由客户端接口限定具体类型
 * @author hongqy
 */
public record ItinerarySearchResult<T>(ItinerarySearchProvider provider, OffsetDateTime searchedAt,
                                       List<T> candidates, SearchPagination pagination,
                                       int discardedItemCount, List<String> warnings) {
}
