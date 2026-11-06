package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.UserProfile;
import com.fons.cloud.ai.trip.domain.mapper.UserProfileMapper;
import com.fons.cloud.ai.trip.domain.service.UserProfileDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class UserProfileDomainServiceImpl extends ServiceImpl<UserProfileMapper, UserProfile> implements UserProfileDomainService {
    @Override
    public UserProfile getPreferenceProfile(String userId) {
        return baseMapper.selectPreferenceProfile(userId);
    }

    @Override
    public void ensurePreferenceProfile(String userId) {
        baseMapper.ensurePreferenceProfile(userId);
    }

    @Override
    public boolean updatePreferencesWithVersion(String userId, String json, long version) {
        return baseMapper.updatePreferencesWithVersion(userId, json, version) == 1;
    }
}
