package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.TravelPolicyRule;

/**
 * @author hongqy
 */
public interface TravelPolicyRuleDomainService extends IService<TravelPolicyRule> {

    /**
     * 根据职级区间和政策城市等级查询唯一规则，未匹配时返回 null。
     * 城市等级取值：一线、新一线、其他。
     */
    TravelPolicyRule findByLevelAndCityTier(int levelNum, String cityTier);
}
