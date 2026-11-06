package com.fons.cloud.ai.trip.infrastructure.repository;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 保存当前用户和会话下每个规划的最新审核报告。
 * 同一planId再次审核时覆盖旧报告，不保存审核历史版本；当前不设置TTL。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryReviewRepository {

    private static final String KEY_PREFIX = "trip:itinerary:reviews:v2:";
    private final RedissonClient redissonClient;

    /**
     * 保存应用修复停止规则后的完整审核报告。
     *
     * @throws SystemIntervalException 报告契约无效、序列化失败或Redis写入失败时抛出
     */
    public void saveLatest(String userId, String orderId, String conversationId, ItineraryReviewResult result) {
        requireScope(userId, orderId, conversationId);
        Assert.isTrue(result != null && StringUtils.isNotBlank(result.getPlanId())
                        && StringUtils.isNotBlank(result.getReviewId()),
                () -> protocolError("审核结果、planId和reviewId不能为空"));
        try {
            reviewMap(userId, orderId, conversationId).put(result.getPlanId(), JSON.toJSONString(result));
        } catch (JSONException e) {
            throw SystemIntervalException.of("行程审核结果序列化失败", e);
        } catch (RedisException e) {
            log.warn("[ItineraryReviewRepository] 保存审核结果失败，planId={}, exceptionType={}",
                    result.getPlanId(), e.getClass().getSimpleName());
            throw SystemIntervalException.of("行程审核结果保存失败", e);
        }
    }

    /**
     * 按可信归属和planId读取最新审核报告。
     *
     * @return 最新审核结果；不存在时返回null
     * @throws SystemIntervalException 存储数据无效或Redis读取失败时抛出
     */
    public ItineraryReviewResult findLatest(String userId, String orderId, String conversationId, String planId) {
        requireScope(userId, orderId, conversationId);
        Assert.notBlank(planId, () -> protocolError("planId不能为空"));
        String normalizedPlanId = planId.trim();
        try {
            String json = reviewMap(userId, orderId, conversationId).get(normalizedPlanId);
            if (json == null) {
                return null;
            }
            ItineraryReviewResult result = JSON.parseObject(json, ItineraryReviewResult.class);
            Assert.isTrue(result != null && normalizedPlanId.equals(result.getPlanId())
                            && StringUtils.isNotBlank(result.getReviewId())
                            && result.getExecutionStatus() != null && result.getNextAction() != null,
                    () -> protocolError("行程审核结果数据不完整或与planId不一致"));
            return result;
        } catch (JSONException e) {
            throw SystemIntervalException.of("行程审核结果JSON格式无效", e);
        } catch (RedisException e) {
            log.warn("[ItineraryReviewRepository] 读取审核结果失败，planId={}, exceptionType={}",
                    normalizedPlanId, e.getClass().getSimpleName());
            throw SystemIntervalException.of("行程审核结果读取失败", e);
        }
    }

    /** 读取旧用户会话下的最终审核，只用于历史页面兼容。 */
    public ItineraryReviewResult findLegacyLatest(String userId, String conversationId, String planId) {
        Assert.isTrue(StringUtils.isNoneBlank(userId, conversationId, planId),
                () -> protocolError("用户、会话和方案标识不能为空"));
        try {
            String key = "trip:itinerary:reviews:{" + DigestUtil.sha256Hex(JSON.toJSONBytes(
                    new TreeMap<>(Map.of("userId", userId.trim(), "conversationId", conversationId.trim())))) + "}";
            String json = redissonClient.<String, String>getMap(key, StringCodec.INSTANCE).get(planId.trim());
            if (json == null) {
                return null;
            }
            ItineraryReviewResult result = JSON.parseObject(json, ItineraryReviewResult.class);
            Assert.isTrue(result != null && planId.trim().equals(result.getPlanId())
                            && StringUtils.isNotBlank(result.getReviewId())
                            && result.getExecutionStatus() != null && result.getNextAction() != null,
                    () -> protocolError("历史审核结果数据不完整或与planId不一致"));
            return result;
        } catch (JSONException | RedisException e) {
            throw SystemIntervalException.of("读取历史审核结果失败", e);
        }
    }

    private RMap<String, String> reviewMap(String userId, String orderId, String conversationId) {
        String key = KEY_PREFIX + "{" + DigestUtil.sha256Hex(JSON.toJSONBytes(List.of(userId.trim(), orderId.trim(), conversationId.trim()))) + "}";
        return redissonClient.getMap(key, StringCodec.INSTANCE);
    }

    /** 按用户、差旅单和会话隔离运行中的规划数据。 */
    private void requireScope(String userId, String orderId, String conversationId) {
        Assert.isTrue(StringUtils.isNoneBlank(userId, orderId, conversationId),
                () -> SystemIntervalException.of("用户、差旅单和会话标识不能为空"));
    }

    private SystemIntervalException protocolError(String message) {
        return SystemIntervalException.of(message);
    }
}
