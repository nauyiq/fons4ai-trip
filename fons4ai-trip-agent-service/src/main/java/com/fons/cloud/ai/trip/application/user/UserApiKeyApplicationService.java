package com.fons.cloud.ai.trip.application.user;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.BusinessProvider;
import com.fons.cloud.ai.trip.domain.entity.UserApiKey;
import com.fons.cloud.ai.trip.domain.service.UserApiKeyDomainService;
import com.fons.cloud.ai.trip.infrastructure.config.properties.BusinessProviderConfigProperties;
import com.fons.cloud.ai.trip.infrastructure.config.properties.BusinessProviderConfigProperties.ProviderInfo;
import com.fons.cloud.ai.trip.infrastructure.util.ApiKeyCipher;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 用户第三方凭据应用服务，不使用会话或 Redis 缓存。
 *
 * @deprecated 企业统一管理供应商凭据，个人API Key能力仅保留兼容，新业务不再使用。
 * @author hongqy
 */
@Service
@RequiredArgsConstructor
@Deprecated
public class UserApiKeyApplicationService {

    /** 密文字段长度为512，限制明文UTF-8字节数以容纳GCM的IV、认证标签和Base64开销。 */
    private static final int MAX_KEY_BYTES = 300;

    private final UserApiKeyDomainService userApiKeyDomainService;
    private final ApiKeyCipher apiKeyCipher;
    private final BusinessProviderConfigProperties businessProviderConfigProperties;

    /** 检查本地是否配置可解密的非空凭据，不校验远端权限或过期状态。 */
    public boolean hasApiKey(String userId, BusinessProvider provider) {
        return StringUtils.isNotBlank(getApiKey(userId, provider));
    }

    /** 获取后端调用使用的明文凭据，不存在时返回 null；禁止回显至工具结果或日志。 */
    public String getApiKey(String userId, BusinessProvider provider) {
        validateIdentity(userId, provider);
        ProviderInfo providerInfo = businessProviderConfigProperties.requireProviderInfo(provider);
        UserApiKey entry = userApiKeyDomainService.findByUserIdAndProvider(userId.trim(), providerInfo.getName().trim());
        if (entry == null) {
            return null;
        }
        Assert.notBlank(entry.getApiKeyEnc(), () -> SystemIntervalException.of("用户API Key密文为空"));
        return apiKeyCipher.decrypt(entry.getApiKeyEnc());
    }

    /** 校验用户明确提供的凭据并加密保存，已有凭据原子覆盖；不进行远端认证。 */
    public void saveApiKey(String userId, BusinessProvider provider, String apiKey) {
        validateIdentity(userId, provider);
        ProviderInfo providerInfo = businessProviderConfigProperties.requireProviderInfo(provider);
        String keyPrefix = providerInfo.getKeyPrefix().trim();
        String value = StringUtils.trimToEmpty(apiKey);
        Assert.isTrue(value.startsWith(keyPrefix) && value.length() > keyPrefix.length(),
                () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "API Key不能为空且应以" + keyPrefix + "开头"));
        Assert.isTrue(value.chars().noneMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)),
                () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "API Key不能包含空白或控制字符"));
        Assert.isTrue(value.getBytes(StandardCharsets.UTF_8).length <= MAX_KEY_BYTES,
                () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "API Key长度超出支持范围"));
        String encrypted = apiKeyCipher.encrypt(value);
        Assert.isTrue(userApiKeyDomainService.saveEncryptedKey(userId.trim(), providerInfo.getName().trim(), encrypted),
                () -> SystemIntervalException.of("用户API Key保存失败"));
    }

    private void validateIdentity(String userId, BusinessProvider provider) {
        Assert.notBlank(userId, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "用户ID不能为空"));
        Assert.notNull(provider, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "凭据提供商不能为空"));
    }
}
