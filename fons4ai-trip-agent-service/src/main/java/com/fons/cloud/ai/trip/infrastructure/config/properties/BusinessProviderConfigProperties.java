package com.fons.cloud.ai.trip.infrastructure.config.properties;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.BusinessProvider;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.Getter;
import lombok.Setter;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 第三方服务提供者信息
 *
 * @deprecated 企业统一管理供应商凭据，个人API Key能力仅保留兼容，新业务不再使用。
 * @author hongqy
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "trip.business")
@Deprecated
public class BusinessProviderConfigProperties {

    /**
     * 第三方服务提供者信息, 保证展示顺序的一致
     */
    private Map<BusinessProvider, ProviderInfo> providers = new LinkedHashMap<>();

    /**
     * 获取完整的提供商配置；缺失配置属于系统异常，不能当成用户未配置凭据。
     */
    public ProviderInfo requireProviderInfo(BusinessProvider provider) {
        Assert.notNull(provider, () -> SystemIntervalException.of("第三方服务提供商不能为空"));
        ProviderInfo info = providers == null ? null : providers.get(provider);
        Assert.notNull(info, () -> SystemIntervalException.of("未配置第三方服务提供商：" + provider));
        Assert.isTrue(StringUtils.isNoneBlank(info.getName(), info.getGuideUrl(), info.getKeyPrefix()), () -> SystemIntervalException.of("第三方服务提供商配置不完整：" + provider));
        return info;
    }

    @Getter
    @Setter
    public static class ProviderInfo {

        /**
         * 凭据持久化使用的提供商标识，与 user_api_key.provider 对齐，例如 tuniu-cli。
         */
        private String name;

        /**
         * 获取服务商API密钥的指南URL
         */
        private String guideUrl;

        /**
         * 第三方服务商的API密钥前缀
         */
        private String keyPrefix;

    }

}
