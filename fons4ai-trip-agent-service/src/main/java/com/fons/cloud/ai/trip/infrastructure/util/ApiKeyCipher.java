package com.fons.cloud.ai.trip.infrastructure.util;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 用户凭据加解密组件。新密文使用 AES-GCM，随机 IV 与密文一起进行 Base64 编码。
 * 无默认加密密钥；保存或读取已配置凭据前必须设置 trip.api-key.encrypt-secret。
 * 此密文格式与 Gogo 的 AES-ECB 格式不同，不直接兼容旧密文导入。
 *
 * @author hongqy
 */
@Component
public class ApiKeyCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String FORMAT_PREFIX = "gcm:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    @Value("${trip.api-key.encrypt-secret:}")
    private String encryptSecret;

    /** 使用随机 IV 加密，返回带格式标识的密文。 */
    public String encrypt(String plainText) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return FORMAT_PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException e) {
            throw SystemIntervalException.of("用户API Key加密失败", e);
        }
    }

    /** 校验密文完整性并解密，仅供后端调用，不将明文传给 LLM。 */
    public String decrypt(String encryptedText) {
        Assert.isTrue(encryptedText != null && encryptedText.startsWith(FORMAT_PREFIX),
                () -> SystemIntervalException.of("用户API Key密文格式不受支持"));
        try {
            byte[] payload = Base64.getDecoder().decode(encryptedText.substring(FORMAT_PREFIX.length()));
            Assert.isTrue(payload.length > IV_LENGTH + TAG_BITS / Byte.SIZE,
                    () -> SystemIntervalException.of("用户API Key密文不完整"));
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            byte[] iv = new byte[IV_LENGTH];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // 不附带解码异常原文，避免底层异常回显密文。
            throw SystemIntervalException.of("用户API Key解密失败，请检查加密配置或重新配置凭据");
        }
    }

    private SecretKeySpec resolveKey() throws GeneralSecurityException {
        Assert.notBlank(encryptSecret, () -> SystemIntervalException.of("未配置trip.api-key.encrypt-secret"));
        return new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                .digest(encryptSecret.getBytes(StandardCharsets.UTF_8)), "AES");
    }
}
