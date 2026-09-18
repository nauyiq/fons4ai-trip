package com.fons.cloud.ai.trip.common.response;

/**
 * 用户第三方凭据配置状态，不包含 Key 明文或密文。
 *
 * @param provider 提供商编码
 * @param hasKey 是否存在可解密的非空凭据；不代表远端校验有效
 * @param guideUrl 获取凭据的引导地址
 * @author hongqy
 */
public record CheckApiKeyResult(String provider, boolean hasKey, String guideUrl) {
}
