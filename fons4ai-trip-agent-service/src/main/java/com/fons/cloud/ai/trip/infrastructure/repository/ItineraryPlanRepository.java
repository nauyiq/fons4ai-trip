package com.fons.cloud.ai.trip.infrastructure.repository;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
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
 * 行程规划结果Redis存储组件。
 * 每个可信用户和根会话使用一个Hash，字段为planId，值为完整的Trip规划结果JSON。
 * 规划结果通过planId精确读取，不向上层暴露Redis Key，当前不设置TTL。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryPlanRepository {

    private static final String KEY_PREFIX = "trip:itinerary:plans:v2:";
    private final RedissonClient redissonClient;

    /**
     * 保存完整规划结果。
     *
     * @param userId 可信用户标识
     * @param orderId 差旅单标识
     * @param conversationId 根会话标识
     * @param result 完整规划结果，必须包含planId
     * @throws SystemIntervalException 存储契约无效、序列化失败或Redis写入失败时抛出
     */
    public void save(String userId, String orderId, String conversationId, ItineraryPlanningResult result) {
        requireScope(userId, orderId, conversationId);
        Assert.isTrue(result != null && StringUtils.isNotBlank(result.getPlanId()),
                () -> protocolError("规划结果和planId不能为空"));
        try {
            planMap(userId, orderId, conversationId).put(result.getPlanId(), JSON.toJSONString(result));
        } catch (JSONException e) {
            throw SystemIntervalException.of("行程规划结果序列化失败", e);
        } catch (RedisException e) {
            log.warn("[ItineraryPlanRepository] 保存规划结果失败，planId={}, exceptionType={}",
                    result.getPlanId(), e.getClass().getSimpleName());
            throw SystemIntervalException.of("行程规划结果保存失败", e);
        }
    }

    /**
     * 按可信归属和planId读取完整规划结果。
     *
     * @param userId 可信用户标识
     * @param orderId 差旅单标识
     * @param conversationId 根会话标识
     * @param planId 规划结果标识
     * @return 完整规划结果；不存在时返回null
     * @throws SystemIntervalException 存储数据无效或Redis读取失败时抛出
     */
    public ItineraryPlanningResult findById(String userId, String orderId, String conversationId, String planId) {
        requireScope(userId, orderId, conversationId);
        Assert.notBlank(planId, () -> protocolError("planId不能为空"));
        try {
            String resultJson = planMap(userId, orderId, conversationId).get(planId.trim());
            if (resultJson == null) {
                return null;
            }
            ItineraryPlanningResult result = JSON.parseObject(resultJson, ItineraryPlanningResult.class);
            Assert.isTrue(result != null && planId.trim().equals(result.getPlanId()),
                    () -> protocolError("行程规划结果与planId不一致"));
            return result;
        } catch (JSONException e) {
            throw SystemIntervalException.of("行程规划结果JSON格式无效", e);
        } catch (RedisException e) {
            log.warn("[ItineraryPlanRepository] 读取规划结果失败，planId={}, exceptionType={}",
                    planId, e.getClass().getSimpleName());
            throw SystemIntervalException.of("行程规划结果读取失败", e);
        }
    }

    /** 旧会话页面只读兼容，调用方须先验证会话归属。 */
    public ItineraryPlanningResult findLegacyById(String userId, String conversationId, String planId) {
        Assert.isTrue(StringUtils.isNoneBlank(userId, conversationId, planId),
                () -> protocolError("用户、会话和方案标识不能为空"));
        try {
            String key = "trip:itinerary:plans:{" + DigestUtil.sha256Hex(JSON.toJSONBytes(
                    new TreeMap<>(Map.of("userId", userId.trim(), "conversationId", conversationId.trim())))) + "}";
            String json = redissonClient.<String, String>getMap(key, StringCodec.INSTANCE).get(planId.trim());
            if (json == null) {
                return null;
            }
            ItineraryPlanningResult result = JSON.parseObject(json, ItineraryPlanningResult.class);
            Assert.isTrue(result != null && planId.trim().equals(result.getPlanId()),
                    () -> protocolError("行程规划结果与planId不一致"));
            return result;
        } catch (JSONException | RedisException e) {
            throw SystemIntervalException.of("读取历史行程规划失败", e);
        }
    }

    private RMap<String, String> planMap(String userId, String orderId, String conversationId) {
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
