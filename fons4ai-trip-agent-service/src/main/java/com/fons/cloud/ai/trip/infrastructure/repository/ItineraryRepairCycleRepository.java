package com.fons.cloud.ai.trip.infrastructure.repository;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.dto.ItineraryRepairCycle;
import com.fons.cloud.ai.trip.common.exception.TripExceptions;
import com.fons.cloud.common.exception.SystemIntervalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 保存当前用户会话的行程修复次数与流程状态，不保存历史方案副本。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryRepairCycleRepository {

    private static final String KEY_PREFIX = "trip:itinerary:repair-cycle:v2:";
    private final RedissonClient redissonClient;

    /**
     * 读取当前用户和会话的修复状态。
     *
     * @return 当前状态；未开始规划时返回null
     * @throws SystemIntervalException Redis读取失败或状态数据无效时抛出
     */
    public ItineraryRepairCycle find(String userId, String orderId, String conversationId) {
        requireScope(userId, orderId, conversationId);
        try {
            String json = bucket(userId, orderId, conversationId).get();
            if (json == null) {
                return null;
            }
            ItineraryRepairCycle cycle = JSON.parseObject(json, ItineraryRepairCycle.class);
            Assert.isTrue(cycle != null && cycle.scope() != null
                            && StringUtils.isNotBlank(cycle.planId())
                            && StringUtils.isNotBlank(cycle.runId())
                            && cycle.status() != null && cycle.replanCount() >= 0
                            && cycle.reviewRetryCount() >= 0,
                    () -> TripExceptions.system("行程修复状态数据不完整"));
            return cycle;
        } catch (JSONException e) {
            throw TripExceptions.system("行程修复状态JSON格式无效", e);
        } catch (RedisException e) {
            log.warn("[ItineraryRepairCycleRepository] 读取状态失败，userId={}, exceptionType={}",
                    userId, e.getClass().getSimpleName());
            throw TripExceptions.system("行程修复状态读取失败", e);
        }
    }

    /**
     * 保存当前流程的最小状态，不覆盖完整规划结果仓储。
     *
     * @throws SystemIntervalException Redis写入或JSON序列化失败时抛出
     */
    public void save(String userId, String orderId, String conversationId, ItineraryRepairCycle cycle) {
        requireScope(userId, orderId, conversationId);
        Assert.notNull(cycle, () -> TripExceptions.system("行程修复状态不能为空"));
        try {
            bucket(userId, orderId, conversationId).set(JSON.toJSONString(cycle));
        } catch (JSONException e) {
            throw TripExceptions.system("行程修复状态序列化失败", e);
        } catch (RedisException e) {
            log.warn("[ItineraryRepairCycleRepository] 保存状态失败，userId={}, exceptionType={}",
                    userId, e.getClass().getSimpleName());
            throw TripExceptions.system("行程修复状态保存失败", e);
        }
    }

    /** 旧会话页面只读取当时的修复状态，不能用于新规划。 */
    public ItineraryRepairCycle findLegacy(String userId, String conversationId) {
        Assert.isTrue(StringUtils.isNoneBlank(userId, conversationId),
                () -> TripExceptions.system("用户和会话标识不能为空"));
        try {
            String key = "trip:itinerary:repair-cycle:{" + DigestUtil.sha256Hex(JSON.toJSONBytes(
                    new TreeMap<>(Map.of("userId", userId.trim(), "conversationId", conversationId.trim())))) + "}";
            String json = redissonClient.<String>getBucket(key, StringCodec.INSTANCE).get();
            if (json == null) {
                return null;
            }
            ItineraryRepairCycle cycle = JSON.parseObject(json, ItineraryRepairCycle.class);
            Assert.isTrue(cycle != null && cycle.scope() != null && StringUtils.isNotBlank(cycle.planId())
                            && StringUtils.isNotBlank(cycle.runId()) && cycle.status() != null
                            && cycle.replanCount() >= 0 && cycle.reviewRetryCount() >= 0,
                    () -> TripExceptions.system("历史行程修复状态数据不完整"));
            return cycle;
        } catch (JSONException | RedisException e) {
            throw TripExceptions.system("读取历史行程修复状态失败", e);
        }
    }

    /** 按用户、差旅单和会话隔离运行中的规划数据。 */
    private void requireScope(String userId, String orderId, String conversationId) {
        Assert.isTrue(StringUtils.isNoneBlank(userId, orderId, conversationId),
                () -> TripExceptions.system("用户、差旅单和会话标识不能为空"));
    }

    private RBucket<String> bucket(String userId, String orderId, String conversationId) {
        String key = KEY_PREFIX + "{" + DigestUtil.sha256Hex(JSON.toJSONBytes(List.of(userId.trim(), orderId.trim(), conversationId.trim()))) + "}";
        return redissonClient.getBucket(key, StringCodec.INSTANCE);
    }
}
