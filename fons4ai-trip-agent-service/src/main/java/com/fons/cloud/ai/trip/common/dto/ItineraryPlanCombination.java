package com.fons.cloud.ai.trip.common.dto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 一组可计算的“去程 + 住宿 + 返程”候选组合及其客观指标。
 * 本模型只计算候选事实，不包含偏好、政策、体验评分或代表方案标签。
 *
 * @param combinationId 由三个稳定候选ID组成的本次规划内组合标识
 * @param outbound 去程交通候选
 * @param hotel 酒店候选
 * @param inbound 返程交通候选
 * @param metrics 组合的客观费用和时间指标
 * @author hongqy
 */
public record ItineraryPlanCombination(String combinationId,
                                       TransportCandidate outbound,
                                       HotelCandidate hotel,
                                       TransportCandidate inbound,
                                       Metrics metrics) {

    private static final BigDecimal MINUTES_PER_HOUR = BigDecimal.valueOf(60);

    /**
     * 生成全部时间顺序有效的候选组合。
     * 方案总价按一名单程去程票、一间酒店和一名单程返程票计算，与当前结果契约保持一致。
     *
     * @param candidates 已完成业务可计算性过滤的候选集合
     * @return 组合生成结果；全部组合无效时返回明确错误
     */
    public static BuildResult build(ItineraryPlanningCandidates candidates) {
        List<ItineraryPlanCombination> combinations = new ArrayList<>();
        long rejectedCombinationCount = 0;
        for (TransportCandidate outbound : candidates.outbound()) {
            for (HotelCandidate hotel : candidates.hotels()) {
                for (TransportCandidate inbound : candidates.inbound()) {
                    ItineraryPlanCombination combination = create(outbound, hotel, inbound);
                    if (combination == null) {
                        rejectedCombinationCount++;
                    } else {
                        combinations.add(combination);
                    }
                }
            }
        }

        List<String> errors = combinations.isEmpty()
                ? List.of("所有候选组合的返程出发时间均未晚于去程到达时间，无法形成有效往返方案")
                : List.of();
        return new BuildResult(combinations, rejectedCombinationCount, errors);
    }

    private static ItineraryPlanCombination create(TransportCandidate outbound,
                                                   HotelCandidate hotel,
                                                   TransportCandidate inbound) {
        if (!inbound.departureTime().isAfter(outbound.arrivalTime())) {
            return null;
        }

        long stayMinutes = Duration.between(outbound.arrivalTime(), inbound.departureTime()).toMinutes();
        long totalTransitMinutes = Math.addExact(outbound.transitMinutes(), inbound.transitMinutes());
        BigDecimal hotelTotalPrice = hotel.price().amount()
                .multiply(BigDecimal.valueOf(hotel.nights()))
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal totalPrice = outbound.price().amount()
                .add(hotelTotalPrice)
                .add(inbound.price().amount())
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal stayHours = BigDecimal.valueOf(stayMinutes)
                .divide(MINUTES_PER_HOUR, 1, RoundingMode.HALF_UP);
        Metrics metrics = new Metrics(hotelTotalPrice, totalPrice, totalTransitMinutes, stayHours);
        String combinationId = String.join("|", outbound.candidateId(), hotel.candidateId(), inbound.candidateId());
        return new ItineraryPlanCombination(combinationId, outbound, hotel, inbound, metrics);
    }

    /**
     * 组合的客观计算指标，金额统一为CNY。
     *
     * @param hotelTotalPrice 酒店每晚价格乘实际入住晚数
     * @param totalPrice 去程交通、酒店和返程交通价格之和
     * @param totalTransitMinutes 去程和返程交通耗时之和，单位分钟
     * @param stayHours 去程到达至返程出发之间的小时数，保留一位小数
     */
    public record Metrics(BigDecimal hotelTotalPrice,
                          BigDecimal totalPrice,
                          long totalTransitMinutes,
                          BigDecimal stayHours) {
    }

    /** 有效组合、被时间顺序过滤的组合数以及组合级错误。 */
    public record BuildResult(List<ItineraryPlanCombination> combinations,
                              long rejectedCombinationCount,
                              List<String> errors) {

        public BuildResult {
            combinations = List.copyOf(combinations);
            errors = List.copyOf(errors);
        }
    }
}
