package com.fons.cloud.ai.trip.application.business;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.CityTier;
import com.fons.cloud.ai.trip.common.constants.TravelCabinClass;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.TravelPolicy;
import com.fons.cloud.ai.trip.common.dto.TravelPolicyOrderSummary;
import com.fons.cloud.ai.trip.common.response.PolicyCheckResult;
import com.fons.cloud.ai.trip.domain.entity.TravelPolicyRule;
import com.fons.cloud.ai.trip.domain.entity.UserProfile;
import com.fons.cloud.ai.trip.domain.service.TravelPolicyRuleDomainService;
import com.fons.cloud.ai.trip.domain.service.UserProfileDomainService;
import com.fons.cloud.ai.trip.infrastructure.config.properties.TravelPolicyProperties;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 差旅策略应用服务
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelPolicyApplicationService {
    private final UserProfileDomainService userProfileDomainService;
    private final TravelPolicyRuleDomainService travelPolicyRuleDomainService;
    private final TravelPolicyProperties travelPolicyProperties;

    /**
     * 根据用户档案中的职级和目的城市查询差旅政策，不使用会话缓存。
     * 二线及其他城市使用规则表中的“其他”档，结果仍保留实际城市等级。
     */
    public TravelPolicy getPolicy(String userId, String destinationCity) {
        Assert.notBlank(userId, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "用户ID不能为空"));
        UserProfile profile = userProfileDomainService.getById(userId.trim());
        Assert.notNull(profile, () -> BusinessRuntimeException.of(TripAgentResultCode.USER_NOT_EXIST));
        String level = StrUtil.trim(profile.getLevel());
        if (StrUtil.isBlank(level)) {
            throw SystemIntervalException.of("用户未配置职级，无法获取差旅政策：userId=" + userId);
        }

        String city = normalizeCity(destinationCity);
        CityTier cityTier = resolveCityTier(city);
        String policyTier = cityTier.getPolicyTier().getLabel();
        TravelPolicyRule rule = travelPolicyRuleDomainService.findByLevelAndCityTier(parseLevelNum(level), policyTier);
        if (rule == null) {
            throw SystemIntervalException.of("未找到匹配的差旅政策规则：level=" + level + ", cityTier=" + policyTier);
        }
        validateRule(rule);
        log.info("查询差旅政策，userId={}，level={}，city={}，cityTier={}", userId, level, city, cityTier.getLabel());

        return TravelPolicy.builder()
                .policyRuleId(rule.getId() == null ? null : rule.getId().toString())
                .userLevel(level)
                .destinationCity(city)
                .cityTier(cityTier.getLabel())
                .flightClass(rule.getFlightClass())
                .trainSeatClass(rule.getTrainSeatClass())
                .hotelLimit(rule.getHotelLimit())
                .hotelStarLimit(rule.getHotelStarLimit())
                .dailyMealLimit(rule.getDailyMealLimit())
                .dailyTransportLimit(rule.getDailyTransportLimit())
                .approvalThreshold(rule.getApprovalThreshold())
                .advanceBookingDays(rule.getAdvanceBookingDays())
                .build();
    }

    /**
     * 校验订单摘要是否符合差旅政策
     *
     * @param orderSummaryJson 订单摘要
     * @param policy           选择的策略
     */
    public PolicyCheckResult checkCompliance(String orderSummaryJson, TravelPolicy policy) {
        Assert.notBlank(orderSummaryJson, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "订单摘要不能为空"));
        TravelPolicyOrderSummary summary;
        try {
            summary = JSON.parseObject(orderSummaryJson, TravelPolicyOrderSummary.class);
        } catch (JSONException e) {
            throw BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "订单摘要格式无效，请提供合法的订单摘要JSON", e);
        }
        return checkCompliance(summary, policy);
    }

    /**
     * 校验单笔订单的机票舱位、酒店每晚金额或火车席别。
     * 暂不校验星级、餐补、交通补贴、提前预订天数及审批阈值。
     */
    public PolicyCheckResult checkCompliance(TravelPolicyOrderSummary summary, TravelPolicy policy) {
        Assert.notNull(summary, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "订单摘要不能为空"));
        Assert.notNull(policy, () -> SystemIntervalException.of("差旅政策不能为空"));
        BookingType type = BookingType.of(StrUtil.trim(summary.type()));
        Assert.notNull(type, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "订单类型必须为FLIGHT、HOTEL或TRAIN"));
        List<String> violations = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();

        switch (type) {
            case FLIGHT -> checkCabin(summary.flightClass(), policy.getFlightClass(), type,
                    "机票舱位", violations, suggestions);
            case TRAIN -> checkCabin(summary.seatClass(), policy.getTrainSeatClass(), type,
                    "火车席别", violations, suggestions);
            case HOTEL -> {
                if (!Double.isFinite(policy.getHotelLimit()) || policy.getHotelLimit() < 0) {
                    throw SystemIntervalException.of("差旅政策的酒店每晚上限无效");
                }
                Double amount = summary.amount();
                if (amount == null || !Double.isFinite(amount) || amount <= 0) {
                    violations.add("酒店每晚金额缺失或无效，无法校验合规性");
                    suggestions.add("请提供大于0的酒店每晚房费（元），不要使用多晚订单总额");
                } else if (amount > policy.getHotelLimit()) {
                    violations.add("酒店每晚金额超出政策上限：" + policy.getHotelLimit() + "元");
                    suggestions.add("建议选择每晚不超过" + policy.getHotelLimit() + "元的酒店");
                }
            }
        }
        return new PolicyCheckResult(violations.isEmpty(), List.copyOf(violations), List.copyOf(suggestions));
    }

    private void checkCabin(String actual, String allowedSpec, BookingType type, String label,
                            List<String> violations, List<String> suggestions) {
        if (StrUtil.isBlank(allowedSpec)) {
            throw SystemIntervalException.of("差旅政策未配置" + label + "标准");
        }
        if (StrUtil.isBlank(actual)) {
            violations.add(label + "缺失，无法校验合规性");
            suggestions.add("请补充" + label + "后重新校验");
            return;
        }
        String normalized = actual.trim();
        if (TravelCabinClass.isCompliant(type, normalized, allowedSpec)) {
            return;
        }
        // 未知名称只能精确匹配，避免包含关系误判为合规。
        violations.add(label + "不符合政策标准或无法识别：允许" + allowedSpec);
        suggestions.add("请选择政策允许的" + label + "，并提供准确名称");
    }

    private CityTier resolveCityTier(String city) {
        if (travelPolicyProperties.getTier1Cities().contains(city)) {
            return CityTier.TIER_1;
        }
        if (travelPolicyProperties.getNewTier1Cities().contains(city)) {
            return CityTier.NEW_TIER_1;
        }
        if (travelPolicyProperties.getTier2Cities().contains(city)) {
            return CityTier.TIER_2;
        }
        return CityTier.OTHER;
    }

    private String normalizeCity(String city) {
        String value = StrUtil.trim(city);
        return value != null && value.endsWith("市") ? value.substring(0, value.length() - 1) : value;
    }

    /**
     * 职级支持 P7、p7 或纯数字，不拼接格式错误字符串中的零散数字。
     */
    private int parseLevelNum(String level) {
        String digits = level.startsWith("P") || level.startsWith("p") ? level.substring(1) : level;
        if (digits.isEmpty() || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw SystemIntervalException.of("职级格式无效，无法解析数字：" + level);
        }
        try {
            int number = Integer.parseInt(digits);
            Assert.isTrue(number > 0, () -> SystemIntervalException.of("职级数字必须大于0"));
            return number;
        } catch (NumberFormatException e) {
            throw SystemIntervalException.of("职级数字超出范围：" + level, e);
        }
    }

    /**
     * 防止数据库空字段在构建基本类型 DTO 时产生无提示的拆箱异常。
     */
    private void validateRule(TravelPolicyRule rule) {
        if (StrUtil.isBlank(rule.getFlightClass()) || StrUtil.isBlank(rule.getTrainSeatClass())
                || rule.getHotelLimit() == null || rule.getHotelStarLimit() == null
                || rule.getDailyMealLimit() == null || rule.getDailyTransportLimit() == null
                || rule.getApprovalThreshold() == null || rule.getAdvanceBookingDays() == null) {
            throw SystemIntervalException.of("差旅政策规则配置不完整：ruleId=" + rule.getId());
        }
    }
}
