package com.fons.cloud.ai.trip.infrastructure.repository;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.file.api.OssStoreService;
import com.fons.cloud.file.common.FileException;
import com.fons.cloud.file.common.request.OssObjectRequest;
import com.fons.cloud.file.common.request.OssUploadRequest;
import com.fons.cloud.file.common.response.OssObjectResponse;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 将行程方案HTML保存到OSS。对象路径由可信归属和planId确定，
 * 同一方案重新审核后覆盖原页面，不保留审核页面历史版本，也不暴露原始用户或会话标识。
 *
 * @author hongqy
 */
@Component
@RequiredArgsConstructor
public class ItineraryPlanPageStore {

    private static final String OBJECT_PREFIX = "trip/itinerary/plans/";
    private static final String HTML_CONTENT_TYPE = "text/html; charset=UTF-8";
    private static final String REVIEW_ID_METADATA = "trip-review-id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final OssStoreService ossStoreService;

    /**
     * 上传完整的独立HTML文档，并返回实际保存的对象路径。
     *
     * @throws SystemIntervalException 页面数据无效、上传失败或OSS返回路径不一致时抛出
     */
    public String save(CandidateOwner owner, String planId, String reviewId, String html) {
        Assert.notNull(owner, () -> SystemIntervalException.of("行程页面归属不能为空"));
        Assert.isTrue(isSafeId(planId), () -> SystemIntervalException.of("行程页面的规划标识无效"));
        Assert.notBlank(reviewId, () -> SystemIntervalException.of("行程页面的审核标识不能为空"));
        Assert.notBlank(html, () -> SystemIntervalException.of("行程页面HTML不能为空"));

        String objectKey = objectKey(owner, planId);
        byte[] content = html.getBytes(StandardCharsets.UTF_8);
        try {
            OssObjectResponse response = ossStoreService.upload(OssUploadRequest.builder()
                    .objectKey(objectKey)
                    .filename(planId + ".html")
                    .inputStream(new ByteArrayInputStream(content))
                    .metadata(Map.of("content-type", HTML_CONTENT_TYPE, REVIEW_ID_METADATA, reviewId))
                    .build());
            Assert.isTrue(response != null && objectKey.equals(response.getObjectKey()),
                    () -> SystemIntervalException.of("行程页面OSS上传结果缺失或对象路径不一致"));
            return objectKey;
        } catch (FileException e) {
            throw SystemIntervalException.of("行程页面OSS保存失败", e);
        }
    }

    /**
     * 读取当前审核对应的页面。页面不存在或仍属于旧审核时返回null。
     */
    public String read(CandidateOwner owner, String planId, String reviewId) {
        Assert.notNull(owner, () -> SystemIntervalException.of("行程页面归属不能为空"));
        Assert.isTrue(isSafeId(planId), () -> SystemIntervalException.of("行程页面的规划标识无效"));
        Assert.notBlank(reviewId, () -> SystemIntervalException.of("行程页面的审核标识不能为空"));

        String objectKey = objectKey(owner, planId);
        OssObjectRequest request = OssObjectRequest.builder().objectKey(objectKey).build();
        try {
            if (!ossStoreService.exists(request)) {
                return null;
            }
            OssObjectResponse info = ossStoreService.getObjectInfo(request);
            if (info == null || !objectKey.equals(info.getObjectKey())
                    || !reviewId.equals(metadataValue(info.getMetadata(), REVIEW_ID_METADATA))) {
                return null;
            }
            OssObjectResponse page = ossStoreService.download(request);
            Assert.isTrue(page != null && objectKey.equals(page.getObjectKey()) && page.getInputStream() != null,
                    () -> SystemIntervalException.of("行程页面OSS下载结果缺失或对象路径不一致"));
            try (InputStream inputStream = page.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (FileException | IOException e) {
            throw SystemIntervalException.of("行程页面OSS读取失败", e);
        }
    }

    private String objectKey(CandidateOwner owner, String planId) {
        return OBJECT_PREFIX + DigestUtil.sha256Hex(JSON.toJSONBytes(owner)) + "/" + planId + ".html";
    }

    private String metadataValue(Map<String, String> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        return metadata.entrySet().stream()
                .filter(entry -> key.equalsIgnoreCase(entry.getKey())
                        || ("x-amz-meta-" + key).equalsIgnoreCase(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst().orElse(null);
    }

    private boolean isSafeId(String value) {
        return StringUtils.isNotBlank(value) && SAFE_ID.matcher(value).matches();
    }
}
