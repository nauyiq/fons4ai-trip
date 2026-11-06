package com.fons.cloud.ai.trip.common.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 一次Redis读取取得的候选分组快照。
 *
 * @param scope 分组条件
 * @param candidates 已保存候选，按稳定候选ID排序，无候选时为空列表
 * @param searchedAtByCandidateId 各候选的供应商获取时间，键为候选ID
 * @param <T> TransportCandidate或HotelCandidate
 * @author hongqy
 */
public record CandidatePoolSnapshot<T>(ItineraryCandidateScope scope,
                                       List<T> candidates,
                                       Map<String, OffsetDateTime> searchedAtByCandidateId) {

    public CandidatePoolSnapshot {
        candidates = List.copyOf(candidates);
        searchedAtByCandidateId = Map.copyOf(searchedAtByCandidateId);
    }
}
