package com.fons.cloud.ai.trip.common.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一次规划批量读取的固定候选快照。后续补搜不修改本对象，但五个分组不承诺跨Key强一致。
 * 仅汇集搜索事实，不进行偏好评分、差旅政策判断或可用性过滤。
 *
 * @param capturedAt Redis读取完成时间，包含UTC偏移
 * @param outboundFlights 去程机票
 * @param outboundTrains 去程火车
 * @param inboundFlights 返程机票
 * @param inboundTrains 返程火车
 * @param hotels 相同入住日期和人数的酒店
 * @author hongqy
 */
public record ItineraryCandidateSnapshot(OffsetDateTime capturedAt,
                                         CandidatePoolSnapshot<TransportCandidate> outboundFlights,
                                         CandidatePoolSnapshot<TransportCandidate> outboundTrains,
                                         CandidatePoolSnapshot<TransportCandidate> inboundFlights,
                                         CandidatePoolSnapshot<TransportCandidate> inboundTrains,
                                         CandidatePoolSnapshot<HotelCandidate> hotels) {

    /** 汇集去程交通，仅供计算入口使用，是否可参与组合仍需校验。 */
    public List<TransportCandidate> outboundTransports() {
        return java.util.stream.Stream.concat(outboundFlights.candidates().stream(), outboundTrains.candidates().stream()).toList();
    }

    /** 汇集返程交通，仅供计算入口使用，是否可参与组合仍需校验。 */
    public List<TransportCandidate> inboundTransports() {
        return java.util.stream.Stream.concat(inboundFlights.candidates().stream(), inboundTrains.candidates().stream()).toList();
    }
}
