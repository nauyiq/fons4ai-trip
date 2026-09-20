package com.fons.cloud.ai.trip.infrastructure.client.api;

import com.fons.cloud.ai.trip.common.constants.ItinerarySearchProvider;
import com.fons.cloud.ai.trip.common.dto.HotelCandidate;
import com.fons.cloud.ai.trip.common.dto.TransportCandidate;
import com.fons.cloud.ai.trip.common.request.FlightSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelSearchRequest;
import com.fons.cloud.ai.trip.common.request.HotelRoomSearchRequest;
import com.fons.cloud.ai.trip.common.request.TrainSearchRequest;
import com.fons.cloud.ai.trip.common.response.ItinerarySearchResult;

/**
 * 行程搜索客户端策略契约，应用服务仅依赖Trip请求和结果，不感知MCP参数或供应商响应。
 * 实现内部负责服务级凭据、参数校验、协议调用、分页适配以及标准候选转换。
 * 不支持的搜索能力须明确报错，不能静默忽略条件或伪造结果。
 * 候选归属、合并与保存由应用服务负责，不放入客户端或策略接口。
 *
 * @author hongqy
 */
public interface ItinerarySearchClient {

    /**
     * 当前实现对应的供应商，用于应用服务选择策略；不接受LLM任意指定服务端点。
     */
    ItinerarySearchProvider getProvider();

    /**
     * 国内机票单程搜索及翻页；返回每种舱位报价对应的交通候选。
     */
    ItinerarySearchResult<TransportCandidate> searchFlights(FlightSearchRequest request);

    /**
     * 火车票单程搜索及翻页；每个席别报价转换成独立交通候选。
     */
    ItinerarySearchResult<TransportCandidate> searchTrains(TrainSearchRequest request);

    /**
     * 单间酒店搜索及翻页；报价保留起价标记和计价口径，不保证可直接预订。
     */
    ItinerarySearchResult<HotelCandidate> searchHotels(HotelSearchRequest request);

    /**
     * 查询指定酒店的一间房型报价；每个房型的每个报价方案转换成独立候选。
     */
    ItinerarySearchResult<HotelCandidate> searchHotelRooms(HotelRoomSearchRequest request);
}
