package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.UserApiKey;
import com.fons.cloud.ai.trip.domain.mapper.UserApiKeyMapper;
import com.fons.cloud.ai.trip.domain.service.UserApiKeyDomainService;
import org.springframework.stereotype.Service;

/**
 * @deprecated 企业统一管理供应商凭据，个人API Key能力仅保留兼容，新业务不再使用。
 * @author hongqy
 */
@Service
@Deprecated
public class UserApiKeyDomainServiceImpl extends ServiceImpl<UserApiKeyMapper, UserApiKey> implements UserApiKeyDomainService {

    @Override
    public UserApiKey findByUserIdAndProvider(String userId, String provider) {
        return getOne(Wrappers.lambdaQuery(UserApiKey.class)
                .eq(UserApiKey::getUserId, userId)
                .eq(UserApiKey::getProvider, provider));
    }

    @Override
    public boolean saveEncryptedKey(String userId, String provider, String encryptedKey) {
        return baseMapper.upsertEncryptedKey(userId, provider, encryptedKey) > 0;
    }
}
