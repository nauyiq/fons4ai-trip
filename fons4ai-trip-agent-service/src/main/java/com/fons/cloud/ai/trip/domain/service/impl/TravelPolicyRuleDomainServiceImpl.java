package com.fons.cloud.ai.trip.domain.service.impl;

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
}
