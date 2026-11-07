package com.fons.cloud.ai.trip.infrastructure.repository;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.trip.common.exception.TripExceptions;
import com.fons.cloud.common.exception.SystemIntervalException;
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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 将行程方案HTML保存到OSS。新发布使用唯一对象路径，旧会话页面按原路径只读兼容；
 * 路径不暴露原始用户、差旅单或会话标识。
 *
 * @author hongqy
 */
@Component
@RequiredArgsConstructor
public class ItineraryPlanPageStore {

    private static final String OBJECT_PREFIX = "trip/itinerary/plans/";
    private static final String PUBLISHED_PREFIX = "trip/itinerary/published/";
    private static final String HTML_CONTENT_TYPE = "text/html; charset=UTF-8";
    private static final String REVIEW_ID_METADATA = "trip-review-id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");

    private final OssStoreService ossStoreService;

    /**
     * 上传完整的独立HTML文档，并返回实际保存的对象路径。
     *
     * @throws SystemIntervalException 页面数据无效、上传失败或OSS返回路径不一致时抛出
     */
    public String save(String userId, String conversationId, String planId, String reviewId, String html) {
        Assert.notBlank(userId, () -> TripExceptions.system("行程页面用户不能为空"));
        Assert.notBlank(conversationId, () -> TripExceptions.system("行程页面会话不能为空"));
        Assert.isTrue(isSafeId(planId), () -> TripExceptions.system("行程页面的规划标识无效"));
        Assert.notBlank(reviewId, () -> TripExceptions.system("行程页面的审核标识不能为空"));
        Assert.notBlank(html, () -> TripExceptions.system("行程页面HTML不能为空"));

        String objectKey = objectKey(userId, conversationId, planId);
        upload(objectKey, planId, reviewId, html);
        return objectKey;
    }

    /** 每次发布使用独立对象，数据库提交失败不会覆盖旧页面。 */
    public String savePublished(String userId, String orderId, String planId, String reviewId, String html) {
        Assert.isTrue(StringUtils.isNoneBlank(userId, orderId, html), () -> TripExceptions.system("发布页面的用户、差旅单或内容不能为空"));
        Assert.isTrue(isSafeId(planId) && isSafeId(reviewId), () -> TripExceptions.system("发布页面的规划或审核标识无效"));
        String scope = DigestUtil.sha256Hex(JSON.toJSONBytes(java.util.List.of(userId.trim(), orderId.trim())));
        String objectKey = PUBLISHED_PREFIX + scope + "/" + planId + "/" + reviewId + "/" + UUID.randomUUID() + ".html";
        upload(objectKey, planId, reviewId, html);
        return objectKey;
    }

    private void upload(String objectKey, String planId, String reviewId, String html) {
        byte[] content = html.getBytes(StandardCharsets.UTF_8);
        try {
            OssObjectResponse response = ossStoreService.upload(OssUploadRequest.builder()
                    .objectKey(objectKey)
                    .filename(planId + ".html")
                    .inputStream(new ByteArrayInputStream(content))
                    .metadata(Map.of("content-type", HTML_CONTENT_TYPE, REVIEW_ID_METADATA, reviewId))
                    .build());
            Assert.isTrue(response != null && objectKey.equals(response.getObjectKey()),
                    () -> TripExceptions.system("行程页面OSS上传结果缺失或对象路径不一致"));
        } catch (FileException e) {
            throw TripExceptions.system("行程页面OSS保存失败", e);
        }
    }

    /**
     * 读取当前审核对应的页面。页面不存在或仍属于旧审核时返回null。
     */
    public String read(String userId, String conversationId, String planId, String reviewId) {
        Assert.notBlank(userId, () -> TripExceptions.system("行程页面用户不能为空"));
        Assert.notBlank(conversationId, () -> TripExceptions.system("行程页面会话不能为空"));
        Assert.isTrue(isSafeId(planId), () -> TripExceptions.system("行程页面的规划标识无效"));
        Assert.notBlank(reviewId, () -> TripExceptions.system("行程页面的审核标识不能为空"));

        return readObject(objectKey(userId, conversationId, planId), reviewId);
    }

    /** 读取数据库中已发布页面的对象，路径只能由服务端保存的记录提供。 */
    public String readPublished(String objectKey, String reviewId) {
        Assert.isTrue(StringUtils.isNotBlank(objectKey) && objectKey.startsWith(PUBLISHED_PREFIX)
                        && isSafeId(reviewId), () -> TripExceptions.system("已发布页面的对象或审核标识无效"));
        String html = readObject(objectKey, reviewId);
        Assert.notNull(html, () -> TripExceptions.system("已发布行程页面不存在或审核标识不一致"));
        return html;
    }

    private String readObject(String objectKey, String reviewId) {
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
                    () -> TripExceptions.system("行程页面OSS下载结果缺失或对象路径不一致"));
            try (InputStream inputStream = page.getInputStream()) {
                return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (FileException | IOException e) {
            throw TripExceptions.system("行程页面OSS读取失败", e);
        }
    }

    private String objectKey(String userId, String conversationId, String planId) {
        // 旧页面按 CandidateOwner 的 JSON 字节计算路径，字段顺序和去空格行为必须保持一致。
        JSONObject identity = new JSONObject();
        identity.put("conversationId", conversationId.trim());
        identity.put("userId", userId.trim());
        return OBJECT_PREFIX + DigestUtil.sha256Hex(JSON.toJSONBytes(identity)) + "/" + planId + ".html";
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
