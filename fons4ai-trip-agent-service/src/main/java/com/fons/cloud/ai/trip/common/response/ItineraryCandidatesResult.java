package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.dto.HotelCandidate;
import com.fons.cloud.ai.trip.common.dto.TransportCandidate;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 返回给行程规划Agent的累计候选快照，不暴露Redis Key、用户ID、会话ID或候选分组内部结构。
 * 交通候选通过type区分机票和火车票；酒店列表起价及具体房型报价可能同时存在，
 * 是否能够参与计算应以候选价格口径和起价标记为准。
 *
 * @param capturedAt 候选读取完成时间
 * @param outbound 去程交通候选
 * @param inbound 返程交通候选
 * @param hotels 酒店及房型报价候选
 * @author hongqy
 */
public record ItineraryCandidatesResult(OffsetDateTime capturedAt,
                                        List<TransportCandidate> outbound,
                                        List<TransportCandidate> inbound,
                                        List<HotelCandidate> hotels) {

    public ItineraryCandidatesResult {
        outbound = List.copyOf(outbound);
        inbound = List.copyOf(inbound);
        hotels = List.copyOf(hotels);
    }
}
