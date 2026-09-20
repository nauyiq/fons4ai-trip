package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.TravelPolicyRule;
import com.fons.cloud.ai.trip.domain.mapper.TravelPolicyRuleMapper;
import com.fons.cloud.ai.trip.domain.service.TravelPolicyRuleDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class TravelPolicyRuleDomainServiceImpl extends ServiceImpl<TravelPolicyRuleMapper, TravelPolicyRule> implements TravelPolicyRuleDomainService {

    @Override
    public TravelPolicyRule findByLevelAndCityTier(int levelNum, String cityTier) {
        // 重叠规则属于配置错误，不使用 LIMIT 1 随机选择政策。
        return getOne(Wrappers.lambdaQuery(TravelPolicyRule.class)
                .le(TravelPolicyRule::getLevelMin, levelNum)
                .ge(TravelPolicyRule::getLevelMax, levelNum)
                .eq(TravelPolicyRule::getCityTier, cityTier));
    }
}
