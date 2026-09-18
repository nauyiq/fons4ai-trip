package com.fons.cloud.ai.trip.common.response;

/**
 * 凭据保存结果，不包含 Key 明文或密文。
 *
 * @param provider 提供商编码
 * @param saved 是否已完成数据库写入，不代表远端认证成功
 * @author hongqy
 */
public record SaveApiKeyResult(String provider, boolean saved) {
}
