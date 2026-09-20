package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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

    /**
     * 校验候选池及调用方引用，并生成应用明确排除项后的规划候选。
     * 本方法不判断价格及时间字段是否可计算，也不执行组合、评分或排序。
     *
     * @param scores 候选偏好评分，null表示中性评分模式
     * @param excludedCandidateIds 明确排除的候选ID
     * @return 候选准备结果；数据错误和引用错误由应用层分别转换为响应码
     */
    public PreparationResult prepareForPlanning(ItineraryPlanRequest.CandidatePreferenceScores scores,
                                                List<String> excludedCandidateIds) {
        List<String> dataErrors = validateCandidatePool();
        List<String> referenceErrors = validateReferences(scores, excludedCandidateIds);
        SelectedCandidates candidates = selectCandidates(excludedCandidateIds);
        if (dataErrors.isEmpty()) {
            dataErrors.addAll(validateSelection(candidates));
        }
        return new PreparationResult(candidates, dataErrors, referenceErrors);
    }

    private List<String> validateCandidatePool() {
        List<String> errors = new ArrayList<>();
        if (outboundTransports().isEmpty()) {
            errors.add("未找到去程交通候选，请先搜索去程机票或火车票");
        }
        if (inboundTransports().isEmpty()) {
            errors.add("未找到返程交通候选，请先搜索返程机票或火车票");
        }
        if (hotels.candidates().isEmpty()) {
            errors.add("未找到住宿候选，请先搜索与行程日期和入住人数一致的酒店");
        }

        Set<String> duplicatedIds = duplicatedIds();
        if (!duplicatedIds.isEmpty()) {
            errors.add("本次候选池包含重复候选ID：" + String.join(",", duplicatedIds));
        }
        return errors;
    }

    private List<String> validateReferences(ItineraryPlanRequest.CandidatePreferenceScores scores,
                                            List<String> excludedCandidateIds) {
        List<String> errors = new ArrayList<>();
        Set<String> transportIds = transportIds();
        Set<String> hotelIds = hotelIds();
        Set<String> allCandidateIds = new HashSet<>(transportIds);
        allCandidateIds.addAll(hotelIds);

        if (scores != null) {
            addUnknownReferences(errors, "交通评分", scores.transportScores(), transportIds);
            addUnknownReferences(errors, "酒店评分", scores.hotelScores(), hotelIds);
        }
        Set<String> unknownExcludedIds = new LinkedHashSet<>(excludedCandidateIds);
        unknownExcludedIds.removeAll(allCandidateIds);
        if (!unknownExcludedIds.isEmpty()) {
            errors.add("排除项包含不属于本次候选池的ID：" + String.join(",", unknownExcludedIds));
        }
        return errors;
    }

    private Set<String> duplicatedIds() {
        Map<String, Integer> idCounts = new HashMap<>();
        outboundTransports().forEach(candidate -> idCounts.merge(candidate.candidateId(), 1, Integer::sum));
        inboundTransports().forEach(candidate -> idCounts.merge(candidate.candidateId(), 1, Integer::sum));
        hotels.candidates().forEach(candidate -> idCounts.merge(candidate.candidateId(), 1, Integer::sum));
        Set<String> duplicatedIds = new LinkedHashSet<>();
        idCounts.forEach((id, count) -> {
            if (count > 1) {
                duplicatedIds.add(id);
            }
        });
        return duplicatedIds;
    }

    private Set<String> transportIds() {
        Set<String> ids = new HashSet<>();
        outboundTransports().stream().map(TransportCandidate::candidateId).forEach(ids::add);
        inboundTransports().stream().map(TransportCandidate::candidateId).forEach(ids::add);
        return ids;
    }

    private Set<String> hotelIds() {
        Set<String> ids = new HashSet<>();
        hotels.candidates().stream().map(HotelCandidate::candidateId).forEach(ids::add);
        return ids;
    }

    private void addUnknownReferences(List<String> errors, String label,
                                      Map<String, ItineraryPlanRequest.PreferenceScore> scores,
                                      Set<String> candidateIds) {
        Set<String> unknownIds = new LinkedHashSet<>(scores.keySet());
        unknownIds.removeAll(candidateIds);
        if (!unknownIds.isEmpty()) {
            errors.add(label + "包含不属于本次候选池的ID：" + String.join(",", unknownIds));
        }
    }

    private SelectedCandidates selectCandidates(List<String> excludedCandidateIds) {
        Set<String> excludedIds = Set.copyOf(excludedCandidateIds);
        return new SelectedCandidates(
                filterCandidates(outboundTransports(), TransportCandidate::candidateId, excludedIds),
                filterCandidates(inboundTransports(), TransportCandidate::candidateId, excludedIds),
                filterCandidates(hotels.candidates(), HotelCandidate::candidateId, excludedIds));
    }

    private <T> List<T> filterCandidates(List<T> candidates, Function<T, String> idReader,
                                         Set<String> excludedIds) {
        return candidates.stream().filter(candidate -> !excludedIds.contains(idReader.apply(candidate))).toList();
    }

    private List<String> validateSelection(SelectedCandidates candidates) {
        List<String> errors = new ArrayList<>();
        if (candidates.outbound().isEmpty()) {
            errors.add("排除候选后没有可参与规划的去程交通");
        }
        if (candidates.inbound().isEmpty()) {
            errors.add("排除候选后没有可参与规划的返程交通");
        }
        if (candidates.hotels().isEmpty()) {
            errors.add("排除候选后没有可参与规划的住宿");
        }
        return errors;
    }

    /** 候选准备结果，数据错误与调用方候选引用错误使用不同的响应语义。 */
    public record PreparationResult(SelectedCandidates candidates,
                                    List<String> dataErrors,
                                    List<String> referenceErrors) {

        public PreparationResult {
            dataErrors = List.copyOf(dataErrors);
            referenceErrors = List.copyOf(referenceErrors);
        }
    }

    /** 应用排除项后，实际参与后续有效性过滤与组合计算的候选集合。 */
    public record SelectedCandidates(List<TransportCandidate> outbound,
                                     List<TransportCandidate> inbound,
                                     List<HotelCandidate> hotels) {

        public SelectedCandidates {
            outbound = List.copyOf(outbound);
            inbound = List.copyOf(inbound);
            hotels = List.copyOf(hotels);
        }
    }
}
