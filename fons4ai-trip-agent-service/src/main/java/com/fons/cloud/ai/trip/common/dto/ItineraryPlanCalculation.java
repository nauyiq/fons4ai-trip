package com.fons.cloud.ai.trip.common.dto;

import cn.hutool.core.util.IdUtil;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.TravelCabinClass;
import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.DimensionWeights;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.HotelOption;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.PreferenceBasis;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.ProposalTag;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Scores;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TransportOption;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TripRequest;
import org.apache.commons.lang3.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 行程组合的纯计算模型，负责政策检查、体验评分、归一化、综合评分和代表方案选择。
 * 不读取外部服务、不访问存储，也不生成面向用户的推荐文案。
 *
 * @author hongqy
 */
public final class ItineraryPlanCalculation {

    private static final BigDecimal SCORE_MAX = BigDecimal.valueOf(100);
    private static final BigDecimal SCORE_NEUTRAL = BigDecimal.valueOf(50);
    private static final BigDecimal WEIGHT_TIME = new BigDecimal("0.20");
    private static final BigDecimal WEIGHT_PRICE = new BigDecimal("0.10");
    private static final BigDecimal WEIGHT_PREFERENCE = new BigDecimal("0.40");
    private static final BigDecimal WEIGHT_EXPERIENCE = new BigDecimal("0.30");
    private static final BigDecimal POLICY_VIOLATION_PENALTY = BigDecimal.valueOf(40);
    private static final BigDecimal POLICY_WARNING_PENALTY = BigDecimal.valueOf(15);
    private static final BigDecimal EXPERIENCE_TOLERANCE = new BigDecimal("1.15");
    private static final int LATE_HOUR = 21;
    private static final int RED_EYE_DEPARTURE_HOUR = 22;
    private static final int RED_EYE_ARRIVAL_HOUR = 6;
    private static final long LONG_COMMUTE_MINUTES = 240;
    private static final long SEVERE_COMMUTE_MINUTES = 360;
    private static final List<String> BAD_WEATHER_KEYWORDS = List.of(
            "暴雨", "大雾", "大风", "雷暴", "暴雪", "雾霾", "台风", "冰雹");

    private ItineraryPlanCalculation() {
    }

    /**
     * 根据真实候选组合和可信请求生成完整规划结果。
     *
     * @param request 已归一化并通过候选引用校验的规划请求
     * @param combinations 已完成可计算性和时间顺序过滤的组合
     * @return 可持久化并供后续审核、展示使用的完整结果
     */
    public static ItineraryPlanningResult calculate(ItineraryPlanRequest request,
                                                    List<ItineraryPlanCombination> combinations) {
        // 1. 逐个组合计算不依赖候选池相对位置的原始结果：
        //    三项候选的偏好平均分、政策违规与提醒、以及红眼/晚到达/长通勤/天气等原始体验分。
        List<RawCombination> rawCombinations = combinations.stream()
                .map(combination -> calculateRaw(request, combination))
                .toList();

        // 2. 统计本次候选池中交通总耗时、方案总价和原始体验分的最大值与最小值，
        //    作为后续min-max归一化的比较范围；政策分和偏好分不参与归一化。
        ScoreRange timeRange = range(rawCombinations,
                raw -> BigDecimal.valueOf(raw.combination().metrics().totalTransitMinutes()));
        ScoreRange priceRange = range(rawCombinations,
                raw -> raw.combination().metrics().totalPrice());
        ScoreRange experienceRange = range(rawCombinations,
                raw -> raw.experience().score());

        // 3. 将耗时、价格和体验转换为0至100的相对分，数值越高越好，
        //    再按时间20%、价格10%、偏好40%、体验30%计算综合分；政策分单独记录，不计入综合分。
        List<ScoredCombination> scoredCombinations = rawCombinations.stream()
                .map(raw -> score(raw, timeRange, priceRange, experienceRange))
                .toList();

        // 4. 分别选择综合最佳、时间优选、价格优选和偏好最佳组合。
        //    同一组合命中多个维度时合并标签，避免向后续审核和展示重复输出相同行程。
        List<Proposal> proposals = buildProposals(scoredCombinations);

        // 5. 组装可持久化的完整规划结果，保存实际请求、政策、权重、组合数量和代表方案快照。
        //    planId用于后续读取与审核，generatedAt统一使用UTC时间。
        return ItineraryPlanningResult.builder()
                .planId("plan_" + IdUtil.fastSimpleUUID())
                .generatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .userRequest(new TripRequest(request.getOrigin(), request.getDestination(),
                        request.getDepartureDate(), request.getReturnDate()))
                .preferences(request.getPreferences())
                .weatherSummary(request.getWeatherSummary())
                .policy(request.getPolicy())
                .dimensionWeights(new DimensionWeights(
                        WEIGHT_TIME, WEIGHT_PRICE, WEIGHT_PREFERENCE, WEIGHT_EXPERIENCE))
                .currency("CNY")
                .combinationCount(scoredCombinations.size())
                .proposals(proposals)
                .build();
    }

    private static RawCombination calculateRaw(ItineraryPlanRequest request,
                                               ItineraryPlanCombination combination) {
        PreferenceResult preference = preference(request.getScores(), combination);
        PolicyResult policy = checkPolicy(request.getPolicy(), combination);
        ExperienceResult experience = evaluateExperience(request.getWeatherSummary(), combination);
        return new RawCombination(combination, preference, policy, experience);
    }

    private static ScoredCombination score(RawCombination raw,
                                           ScoreRange timeRange,
                                           ScoreRange priceRange,
                                           ScoreRange experienceRange) {
        BigDecimal timeScore = normalize(
                BigDecimal.valueOf(raw.combination().metrics().totalTransitMinutes()), timeRange, false);
        BigDecimal priceScore = normalize(raw.combination().metrics().totalPrice(), priceRange, false);
        BigDecimal experienceScore = normalize(raw.experience().score(), experienceRange, true);
        BigDecimal overall = timeScore.multiply(WEIGHT_TIME)
                .add(priceScore.multiply(WEIGHT_PRICE))
                .add(raw.preference().score().multiply(WEIGHT_PREFERENCE))
                .add(experienceScore.multiply(WEIGHT_EXPERIENCE))
                .setScale(2, RoundingMode.HALF_UP);
        return new ScoredCombination(raw, timeScore, priceScore, experienceScore, overall);
    }

    private static PreferenceResult preference(ItineraryPlanRequest.CandidatePreferenceScores scores,
                                               ItineraryPlanCombination combination) {
        PreferenceEntry outbound = preferenceEntry(scores, true, combination.outbound().candidateId());
        PreferenceEntry hotel = preferenceEntry(scores, false, combination.hotel().candidateId());
        PreferenceEntry inbound = preferenceEntry(scores, true, combination.inbound().candidateId());
        BigDecimal score = outbound.score().add(hotel.score()).add(inbound.score())
                .divide(BigDecimal.valueOf(3), 2, RoundingMode.HALF_UP);
        PreferenceBasis basis = new PreferenceBasis(outbound.basis(), hotel.basis(), inbound.basis());
        return new PreferenceResult(score, basis);
    }

    private static PreferenceEntry preferenceEntry(ItineraryPlanRequest.CandidatePreferenceScores scores,
                                                   boolean transport,
                                                   String candidateId) {
        if (scores == null) {
            return new PreferenceEntry(SCORE_NEUTRAL, List.of());
        }
        Map<String, ItineraryPlanRequest.PreferenceScore> scoreMap = transport
                ? scores.transportScores() : scores.hotelScores();
        ItineraryPlanRequest.PreferenceScore score = scoreMap.get(candidateId);
        return score == null
                ? new PreferenceEntry(BigDecimal.ZERO, List.of())
                : new PreferenceEntry(score.score(), score.basis());
    }

    private static PolicyResult checkPolicy(TravelPolicy policy,
                                            ItineraryPlanCombination combination) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (policy == null) {
            warnings.add("本次规划未应用差旅政策");
            return new PolicyResult(null, violations, warnings);
        }

        if (Double.isFinite(policy.getHotelLimit()) && policy.getHotelLimit() > 0
                && combination.hotel().price().amount().compareTo(BigDecimal.valueOf(policy.getHotelLimit())) > 0) {
            violations.add("酒店单晚价格超过政策上限" + formatAmount(policy.getHotelLimit()) + "元");
        }
        if (policy.getHotelStarLimit() > 0 && combination.hotel().starRating() != null
                && combination.hotel().starRating() > policy.getHotelStarLimit()) {
            warnings.add("酒店星级超过政策上限" + policy.getHotelStarLimit() + "星");
        }
        if (Double.isFinite(policy.getApprovalThreshold()) && policy.getApprovalThreshold() > 0
                && combination.metrics().totalPrice().compareTo(BigDecimal.valueOf(policy.getApprovalThreshold())) > 0) {
            violations.add("方案总价超过审批阈值" + formatAmount(policy.getApprovalThreshold()) + "元");
        }
        checkCabinPolicy(combination.outbound(), "去程", policy, warnings);
        checkCabinPolicy(combination.inbound(), "返程", policy, warnings);

        BigDecimal policyScore = SCORE_MAX
                .subtract(POLICY_VIOLATION_PENALTY.multiply(BigDecimal.valueOf(violations.size())))
                .subtract(POLICY_WARNING_PENALTY.multiply(BigDecimal.valueOf(warnings.size())))
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        return new PolicyResult(policyScore, violations, warnings);
    }

    private static void checkCabinPolicy(TransportCandidate candidate,
                                         String direction,
                                         TravelPolicy policy,
                                         List<String> warnings) {
        String allowed = candidate.type() == BookingType.TRAIN
                ? policy.getTrainSeatClass() : policy.getFlightClass();
        String label = candidate.type() == BookingType.TRAIN ? "火车席别" : "机票舱位";
        if (StringUtils.isBlank(allowed)) {
            warnings.add("差旅政策未配置" + label + "标准");
            return;
        }
        if (StringUtils.isBlank(candidate.cabinClass())) {
            warnings.add(direction + label + "缺失，无法判断是否符合政策");
            return;
        }
        if (!isCabinCompliant(candidate.type(), candidate.cabinClass(), allowed)) {
            warnings.add(direction + label + "超出政策允许范围：" + allowed);
        }
    }

    private static boolean isCabinCompliant(BookingType type, String actual, String allowedSpec) {
        TravelCabinClass actualClass = TravelCabinClass.of(type, actual);
        for (String item : allowedSpec.replace('，', ',').replace('/', ',').split(",")) {
            String allowed = item.trim();
            if (allowed.isEmpty()) {
                continue;
            }
            TravelCabinClass allowedClass = TravelCabinClass.of(type, allowed);
            if ((actualClass != null && actualClass.isNoHigherThan(allowedClass))
                    || actual.trim().equalsIgnoreCase(allowed)) {
                return true;
            }
        }
        return false;
    }

    private static ExperienceResult evaluateExperience(String weatherSummary,
                                                       ItineraryPlanCombination combination) {
        BigDecimal score = SCORE_MAX;
        Set<String> flags = new LinkedHashSet<>();
        if (isRedEye(combination.outbound())) {
            score = score.subtract(BigDecimal.valueOf(30));
            flags.add("去程为红眼航班");
        }
        if (isRedEye(combination.inbound())) {
            score = score.subtract(BigDecimal.valueOf(30));
            flags.add("返程为红眼航班");
        }
        if (combination.outbound().arrivalTime().getHour() >= LATE_HOUR) {
            score = score.subtract(BigDecimal.valueOf(15));
            flags.add("去程到达时间较晚");
        }
        if (combination.inbound().type() == BookingType.FLIGHT
                && combination.inbound().departureTime().getHour() >= LATE_HOUR) {
            score = score.subtract(BigDecimal.TEN);
            flags.add("返程航班出发时间较晚");
        }
        score = applyCommutePenalty(score, combination.outbound(), "去程", flags);
        score = applyCommutePenalty(score, combination.inbound(), "返程", flags);
        if (hasBadWeather(weatherSummary)) {
            if (combination.outbound().type() == BookingType.FLIGHT) {
                score = score.subtract(BigDecimal.valueOf(25));
                flags.add("恶劣天气下去程选择飞机");
            }
            if (combination.inbound().type() == BookingType.FLIGHT) {
                score = score.subtract(BigDecimal.valueOf(25));
                flags.add("恶劣天气下返程选择飞机");
            }
        }
        return new ExperienceResult(score.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                List.copyOf(flags));
    }

    private static BigDecimal applyCommutePenalty(BigDecimal score,
                                                  TransportCandidate candidate,
                                                  String direction,
                                                  Set<String> flags) {
        if (candidate.transitMinutes() >= SEVERE_COMMUTE_MINUTES) {
            flags.add(direction + "单程交通耗时超过6小时");
            return score.subtract(BigDecimal.valueOf(25));
        }
        if (candidate.transitMinutes() >= LONG_COMMUTE_MINUTES) {
            flags.add(direction + "单程交通耗时超过4小时");
            return score.subtract(BigDecimal.valueOf(15));
        }
        return score;
    }

    private static boolean isRedEye(TransportCandidate candidate) {
        return candidate.type() == BookingType.FLIGHT
                && (candidate.departureTime().getHour() >= RED_EYE_DEPARTURE_HOUR
                || candidate.arrivalTime().getHour() < RED_EYE_ARRIVAL_HOUR);
    }

    private static boolean hasBadWeather(String weatherSummary) {
        return StringUtils.isNotBlank(weatherSummary)
                && BAD_WEATHER_KEYWORDS.stream().anyMatch(weatherSummary::contains);
    }

    private static ScoreRange range(List<RawCombination> combinations,
                                    Function<RawCombination, BigDecimal> valueReader) {
        BigDecimal min = null;
        BigDecimal max = null;
        for (RawCombination combination : combinations) {
            BigDecimal value = valueReader.apply(combination);
            min = min == null || value.compareTo(min) < 0 ? value : min;
            max = max == null || value.compareTo(max) > 0 ? value : max;
        }
        return new ScoreRange(min, max);
    }

    private static BigDecimal normalize(BigDecimal value, ScoreRange range, boolean higherIsBetter) {
        if (range.max().compareTo(range.min()) == 0) {
            return SCORE_MAX.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal numerator = higherIsBetter
                ? value.subtract(range.min()) : range.max().subtract(value);
        return numerator.multiply(SCORE_MAX)
                .divide(range.max().subtract(range.min()), 2, RoundingMode.HALF_UP);
    }

    private static List<Proposal> buildProposals(List<ScoredCombination> combinations) {
        Map<String, ProposalSelection> selections = new LinkedHashMap<>();
        mergeSelection(selections, pickMax(combinations, ScoredCombination::overall,
                ScoredCombination::preferenceScore), ProposalTag.OVERALL_BEST);
        mergeSelection(selections, pickExperienceAwareMin(combinations,
                combination -> BigDecimal.valueOf(combination.combination().metrics().totalTransitMinutes()),
                combination -> combination.combination().metrics().totalPrice()), ProposalTag.TIME_PREFERRED);
        mergeSelection(selections, pickExperienceAwareMin(combinations,
                combination -> combination.combination().metrics().totalPrice(),
                combination -> BigDecimal.valueOf(combination.combination().metrics().totalTransitMinutes())),
                ProposalTag.PRICE_PREFERRED);
        mergeSelection(selections, pickMax(combinations, ScoredCombination::preferenceScore,
                ScoredCombination::overall), ProposalTag.PREFERENCE_BEST);

        List<ProposalSelection> ordered = new ArrayList<>(selections.values());
        ordered.sort((left, right) -> {
            int compared = right.combination().overall().compareTo(left.combination().overall());
            return compared != 0 ? compared
                    : left.combination().combination().combinationId()
                    .compareTo(right.combination().combination().combinationId());
        });

        List<Proposal> proposals = new ArrayList<>();
        for (int index = 0; index < ordered.size(); index++) {
            ProposalSelection selection = ordered.get(index);
            proposals.add(toProposal("proposal_" + (index + 1), selection));
        }
        return List.copyOf(proposals);
    }

    private static ScoredCombination pickMax(List<ScoredCombination> combinations,
                                             Function<ScoredCombination, BigDecimal> primary,
                                             Function<ScoredCombination, BigDecimal> secondary) {
        ScoredCombination best = null;
        for (ScoredCombination candidate : combinations) {
            if (best == null || compare(candidate, best, primary, secondary) > 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static ScoredCombination pickExperienceAwareMin(
            List<ScoredCombination> combinations,
            Function<ScoredCombination, BigDecimal> primary,
            Function<ScoredCombination, BigDecimal> secondary) {
        ScoredCombination best = pickMin(combinations, primary, secondary);
        if (best.raw().experience().flags().isEmpty()) {
            return best;
        }
        BigDecimal tolerance = primary.apply(best).multiply(EXPERIENCE_TOLERANCE);
        List<ScoredCombination> cleanCandidates = combinations.stream()
                .filter(candidate -> candidate.raw().experience().flags().isEmpty())
                .filter(candidate -> primary.apply(candidate).compareTo(tolerance) <= 0)
                .toList();
        return cleanCandidates.isEmpty() ? best : pickMin(cleanCandidates, primary, secondary);
    }

    private static ScoredCombination pickMin(List<ScoredCombination> combinations,
                                             Function<ScoredCombination, BigDecimal> primary,
                                             Function<ScoredCombination, BigDecimal> secondary) {
        ScoredCombination best = null;
        for (ScoredCombination candidate : combinations) {
            if (best == null || compare(candidate, best, primary, secondary) < 0) {
                best = candidate;
            }
        }
        return best;
    }

    private static int compare(ScoredCombination left,
                               ScoredCombination right,
                               Function<ScoredCombination, BigDecimal> primary,
                               Function<ScoredCombination, BigDecimal> secondary) {
        int compared = primary.apply(left).compareTo(primary.apply(right));
        if (compared == 0) {
            compared = secondary.apply(left).compareTo(secondary.apply(right));
        }
        if (compared == 0) {
            compared = right.combination().combinationId().compareTo(left.combination().combinationId());
        }
        return compared;
    }

    private static void mergeSelection(Map<String, ProposalSelection> selections,
                                       ScoredCombination combination,
                                       ProposalTag tag) {
        ProposalSelection selection = selections.computeIfAbsent(combination.combination().combinationId(),
                ignored -> new ProposalSelection(combination, new LinkedHashSet<>()));
        selection.tags().add(tag);
    }

    private static Proposal toProposal(String proposalId, ProposalSelection selection) {
        ScoredCombination scored = selection.combination();
        ItineraryPlanCombination combination = scored.combination();
        PolicyResult policy = scored.raw().policy();
        ExperienceResult experience = scored.raw().experience();
        return new Proposal(
                proposalId,
                List.copyOf(selection.tags()),
                transportOption(combination.outbound()),
                hotelOption(combination.hotel()),
                transportOption(combination.inbound()),
                new ItineraryPlanningResult.Metrics(
                        combination.metrics().hotelTotalPrice(), combination.metrics().totalPrice(),
                        combination.metrics().totalTransitMinutes(), combination.metrics().stayHours()),
                new Scores(scored.timeScore(), scored.priceScore(), scored.preferenceScore(),
                        policy.score(), experience.score(), scored.experienceScore(), scored.overall()),
                scored.raw().preference().basis(),
                policy.violations(), policy.warnings(), experience.flags());
    }

    private static TransportOption transportOption(TransportCandidate candidate) {
        return new TransportOption(candidate.candidateId(), candidate.type(), candidate.carrier(), candidate.code(),
                candidate.origin(), candidate.destination(), candidate.departureTime(), candidate.arrivalTime(),
                candidate.transitMinutes(), candidate.price().amount(), candidate.cabinClass(), candidate.direct(),
                candidate.refundPolicy());
    }

    private static HotelOption hotelOption(HotelCandidate candidate) {
        return new HotelOption(candidate.candidateId(), candidate.name(), candidate.brand(), candidate.starRating(),
                candidate.roomType(), candidate.checkInDate(), candidate.checkOutDate(), candidate.nights(),
                candidate.price().amount(), candidate.distanceKm(), candidate.breakfastIncluded(),
                candidate.cancelPolicy());
    }

    private static String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    private record PreferenceEntry(BigDecimal score, List<String> basis) {
    }

    private record PreferenceResult(BigDecimal score, PreferenceBasis basis) {
    }

    private record PolicyResult(BigDecimal score, List<String> violations, List<String> warnings) {

        private PolicyResult {
            violations = List.copyOf(violations);
            warnings = List.copyOf(warnings);
        }
    }

    private record ExperienceResult(BigDecimal score, List<String> flags) {
    }

    private record ScoreRange(BigDecimal min, BigDecimal max) {
    }

    private record RawCombination(ItineraryPlanCombination combination,
                                  PreferenceResult preference,
                                  PolicyResult policy,
                                  ExperienceResult experience) {
    }

    private record ScoredCombination(RawCombination raw,
                                     BigDecimal timeScore,
                                     BigDecimal priceScore,
                                     BigDecimal experienceScore,
                                     BigDecimal overall) {

        private ItineraryPlanCombination combination() {
            return raw.combination();
        }

        private BigDecimal preferenceScore() {
            return raw.preference().score();
        }
    }

    private record ProposalSelection(ScoredCombination combination, Set<ProposalTag> tags) {
    }
}
