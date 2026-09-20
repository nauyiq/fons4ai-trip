package com.fons.cloud.ai.trip.common.request;

import com.alibaba.fastjson2.annotation.JSONField;
import com.fons.cloud.ai.trip.common.dto.TravelPolicy;
import com.fons.cloud.common.request.BaseRequest;
import lombok.*;
import org.apache.commons.lang3.StringUtils;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/**
 * 工具层提交给应用层的往返行程规划请求。
 * 工具字符串参数应先完成归一化、日期解析及 JSON 反序列化，再组装本对象。
 * 搜索候选由应用层按可信身份和行程条件读取，不接受模型自行编造候选数据。
 * 第一阶段仅支持单目的城市的“去程 + 住宿 + 返程”，不承接当天往返无住宿场景。
 *
 * <p>本对象只声明数据契约，身份、日期、评分及候选归属校验由工具层和应用层执行。
 * 不承接人工审批、用户方案选择、超时或存储 Key。
 *
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryPlanRequest extends BaseRequest {

    private static final int DEFAULT_ADULT_COUNT = 2;
    private static final BigDecimal MAX_PREFERENCE_SCORE = BigDecimal.valueOf(100);

    /**
     * Java 序列化标识。
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 当前用户 ID，由工具层从可信 RuntimeContext 填入，不接受模型指定。
     */
    private String userId;

    /**
     * 当前业务会话标识，由可信运行上下文填入；用于业务数据隔离，不是模型编造的子 Agent 句柄。
     */
    private String conversationId;

    /**
     * 出发城市，去除首尾空格，与候选搜索、存储及查询使用同一城市标识。
     */
    private String origin;

    /**
     * 目的城市，去除首尾空格；第一阶段仅支持一个目的城市。
     */
    private String destination;

    /**
     * 去程出发日期，已解析为明确日期，按出发地当地日历解释。
     */
    private LocalDate departureDate;

    /**
     * 返程出发日期，按目的地当地日历解释，不早于去程日期；实际住宿必须至少一晚。
     */
    private LocalDate returnDate;

    /**
     * 单间住宿的成人数，null沿用酒店搜索契约的2人；用于准确读取同人数报价。
     */
    private Integer adultCount;

    /**
     * 单间住宿的儿童年龄，null或空列表表示没有儿童；候选读取按排序后的年龄组合匹配。
     */
    private List<Integer> childAges;

    /**
     * 用户明确表达或长期记忆召回的真实偏好，无偏好时为空字符串；计算引擎不自行解读。
     */
    private String preferences;

    /**
     * 候选偏好评分，使用稳定候选 ID 关联真实候选。
     * null 表示中性评分模式，所有参与候选使用 50 分；工具 scores 空白或 auto 应转换为 null。
     * 非 null 表示手动评分模式，未提供评分的候选沿用旧规则按 0 分处理，不自动补成 50。
     * 评分为 0 仅表示低偏好，不表示排除候选。
     */
    private CandidatePreferenceScores scores;

    /**
     * 实际采用的差旅政策，由可信工具适配层按照运行时用户和目的城市查询后填入。
     * 本字段不作为模型工具参数开放，不能接收模型填写或转述的政策标准。
     * 未取得政策不能据此推断没有限制。
     */
    private TravelPolicy policy;

    /**
     * 出发地与目的地的真实天气摘要，无可用数据时为空字符串，不推断为天气良好。
     */
    private String weatherSummary;

    /**
     * 明确排除的交通或酒店候选 ID，null 或空列表表示不额外排除。
     * 应校验 ID 属于本次候选池，组合计算前排除；不得以 scores 中的 0 分替代排除。
     * 工具层后续需增加相应参数，或由应用层承接已确定的修复指令，当前工具入口尚未接入该字段。
     */
    private List<String> excludedCandidateIds;

    /**
     * 候选偏好评分集合，Map 的键为稳定候选 ID，值为具体评分对象。
     * JSON 字段名沿用当前工具声明的 transport_scores、hotel_scores。
     *
     * @param transportScores 去程、返程交通候选的评分；null 或空 Map 表示未提供交通评分
     * @param hotelScores     酒店候选的评分；null 或空 Map 表示未提供酒店评分
     */
    public record CandidatePreferenceScores(
            @JSONField(name = "transport_scores") Map<String, PreferenceScore> transportScores,
            @JSONField(name = "hotel_scores") Map<String, PreferenceScore> hotelScores) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }

    /**
     * 单个候选的偏好评分，不包含时间、价格、政策或天气等客观计算分。
     *
     * @param score 必填，0 至 100，越高越符合用户真实偏好，不允许越界或缺失
     * @param basis 偏好评分理由，来自用户真实偏好及候选事实；无理由时使用空列表，不由计算引擎编写
     */
    public record PreferenceScore(BigDecimal score, List<String> basis) implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;
    }

    @JSONField(serialize = false)
    public List<String> normalizeAndValidate() {
        List<String> errors = new ArrayList<>();
        normalizeBasicFields();
        validateIdentityAndRoute(errors);
        validateDates(errors);
        normalizeAndValidateGuests(errors);
        normalizeAndValidateScores(errors);
        normalizeAndValidateExcludedCandidateIds(errors);
        return errors;
    }

    private void normalizeBasicFields() {
        setUserId(StringUtils.trimToNull(getUserId()));
        setConversationId(StringUtils.trimToNull(getConversationId()));
        setOrigin(StringUtils.trimToNull(getOrigin()));
        setDestination(StringUtils.trimToNull(getDestination()));
        setPreferences(StringUtils.trimToEmpty(getPreferences()));
        setWeatherSummary(StringUtils.trimToEmpty(getWeatherSummary()));
    }

    private void validateIdentityAndRoute(List<String> errors) {
        if (getUserId() == null) {
            errors.add("用户ID不能为空");
        }
        if (getConversationId() == null) {
            errors.add("会话ID不能为空");
        }
        if (getOrigin() == null) {
            errors.add("出发城市不能为空");
        }
        if (getDestination() == null) {
            errors.add("目的城市不能为空");
        }
        if (getOrigin() != null && getDestination() != null
                && getOrigin().equalsIgnoreCase(getDestination())) {
            errors.add("出发城市与目的城市不能相同");
        }
    }

    private void validateDates(List<String> errors) {
        LocalDate departureDate = getDepartureDate();
        LocalDate returnDate = getReturnDate();
        if (departureDate == null) {
            errors.add("去程日期不能为空");
        }
        if (returnDate == null) {
            errors.add("返程日期不能为空");
        }
        if (departureDate == null || returnDate == null) {
            return;
        }
        if (departureDate.isBefore(LocalDate.now())) {
            errors.add("去程日期不能早于当前业务日期");
        }
        if (!returnDate.isAfter(departureDate)) {
            errors.add("返程日期必须晚于去程日期，当前规划至少需要住宿一晚");
        }
    }


    private void normalizeAndValidateGuests(List<String> errors) {
        Integer adultCount = getAdultCount();
        if (adultCount == null) {
            adultCount = DEFAULT_ADULT_COUNT;
            setAdultCount(adultCount);
        }
        if (adultCount <= 0) {
            errors.add("成人数必须大于0");
        }

        List<Integer> childAges = getChildAges();
        if (childAges == null) {
            setChildAges(List.of());
            return;
        }
        if (childAges.stream().anyMatch(age -> age == null || age < 0)) {
            errors.add("儿童年龄不能为空或负数");
            return;
        }
        setChildAges(childAges.stream().sorted().toList());
    }




    private void normalizeAndValidateScores(List<String> errors) {
        ItineraryPlanRequest.CandidatePreferenceScores scores = getScores();
        if (scores == null) {
            return;
        }
        Map<String, ItineraryPlanRequest.PreferenceScore> transportScores = normalizeScoreMap(
                scores.transportScores(), "交通", errors);
        Map<String, ItineraryPlanRequest.PreferenceScore> hotelScores = normalizeScoreMap(
                scores.hotelScores(), "酒店", errors);
        Set<String> duplicateIds = new LinkedHashSet<>(transportScores.keySet());
        duplicateIds.retainAll(hotelScores.keySet());
        if (!duplicateIds.isEmpty()) {
            errors.add("同一候选ID不能同时出现在交通和酒店评分中：" + String.join(",", duplicateIds));
        }
        setScores(new ItineraryPlanRequest.CandidatePreferenceScores(transportScores, hotelScores));
    }

    private Map<String, ItineraryPlanRequest.PreferenceScore> normalizeScoreMap(
            Map<String, ItineraryPlanRequest.PreferenceScore> source, String label, List<String> errors) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, ItineraryPlanRequest.PreferenceScore> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, ItineraryPlanRequest.PreferenceScore> entry : source.entrySet()) {
            String candidateId = StringUtils.trimToNull(entry.getKey());
            if (candidateId == null) {
                errors.add(label + "评分包含空候选ID");
                continue;
            }
            ItineraryPlanRequest.PreferenceScore preferenceScore = entry.getValue();
            if (preferenceScore == null || preferenceScore.score() == null) {
                errors.add(label + "候选" + candidateId + "缺少偏好分");
                continue;
            }
            if (preferenceScore.score().compareTo(BigDecimal.ZERO) < 0
                    || preferenceScore.score().compareTo(MAX_PREFERENCE_SCORE) > 0) {
                errors.add(label + "候选" + candidateId + "的偏好分必须在0至100之间");
            }
            List<String> basis = normalizeBasis(preferenceScore.basis(), label, candidateId, errors);
            if (normalized.put(candidateId, new ItineraryPlanRequest.PreferenceScore(preferenceScore.score(), basis)) != null) {
                errors.add(label + "评分包含重复候选ID：" + candidateId);
            }
        }
        return Collections.unmodifiableMap(normalized);
    }

    private List<String> normalizeBasis(List<String> source, String label, String candidateId, List<String> errors) {
        if (source == null) {
            errors.add(label + "候选" + candidateId + "的偏好理由不能为null，无理由时应使用空列表");
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String item : source) {
            String value = StringUtils.trimToNull(item);
            if (value == null) {
                errors.add(label + "候选" + candidateId + "包含空白偏好理由");
            } else {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private void normalizeAndValidateExcludedCandidateIds(List<String> errors) {
        List<String> source = getExcludedCandidateIds();
        if (source == null || source.isEmpty()) {
            setExcludedCandidateIds(List.of());
            return;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String item : source) {
            String candidateId = StringUtils.trimToNull(item);
            if (candidateId == null) {
                errors.add("排除候选ID不能为空");
            } else {
                normalized.add(candidateId);
            }
        }
        setExcludedCandidateIds(List.copyOf(normalized));
    }


}
