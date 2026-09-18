package com.fons.cloud.ai.trip.common.response;

/**
 * 凭据保存结果，不包含 Key 明文或密文。
 *
 * @param provider 提供商编码
 * @param saved 是否已完成数据库写入，不代表远端认证成功
 * @deprecated 企业统一管理供应商凭据，个人API Key能力仅保留兼容，新业务不再使用。
 * @author hongqy
 */
@Deprecated
public record SaveApiKeyResult(String provider, boolean saved) {
}
