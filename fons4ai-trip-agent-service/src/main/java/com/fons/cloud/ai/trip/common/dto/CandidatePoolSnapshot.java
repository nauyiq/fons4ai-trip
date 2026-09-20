package com.fons.cloud.ai.trip.common.dto;

import java.util.List;

/**
 * 一次Redis读取取得的候选分组快照。
 *
 * @param scope 分组条件
 * @param candidates 已保存候选，按稳定候选ID排序，无候选时为空列表
 * @param <T> TransportCandidate或HotelCandidate
 * @author hongqy
 */
public record CandidatePoolSnapshot<T>(ItineraryCandidateScope scope,
                                       List<T> candidates) {

    public CandidatePoolSnapshot {
        candidates = List.copyOf(candidates);
    }
}
