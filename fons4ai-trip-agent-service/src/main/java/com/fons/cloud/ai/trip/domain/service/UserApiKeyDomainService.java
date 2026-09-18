package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.UserApiKey;

/**
 * @author hongqy
 */
public interface UserApiKeyDomainService extends IService<UserApiKey> {

    /** 按用户和提供商查询凭据；不存在时返回 null。 */
    UserApiKey findByUserIdAndProvider(String userId, String provider);

    /** 原子写入或覆盖加密凭据，返回是否成功写入。 */
    boolean saveEncryptedKey(String userId, String provider, String encryptedKey);
}
