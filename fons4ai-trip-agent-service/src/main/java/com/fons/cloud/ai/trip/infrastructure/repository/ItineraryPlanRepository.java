package com.fons.cloud.ai.trip.infrastructure.repository;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
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

    private static final String KEY_PREFIX = "trip:itinerary:plans:";
    private final RedissonClient redissonClient;

    /**
     * 保存完整规划结果。
     *
     * @param owner 规划结果的可信用户和根会话归属
     * @param result 完整规划结果，必须包含planId
     * @throws SystemIntervalException 存储契约无效、序列化失败或Redis写入失败时抛出
     */
    public void save(CandidateOwner owner, ItineraryPlanningResult result) {
        Assert.notNull(owner, () -> protocolError("规划结果归属不能为空"));
        Assert.isTrue(result != null && StringUtils.isNotBlank(result.getPlanId()),
                () -> protocolError("规划结果和planId不能为空"));
        try {
            planMap(owner).put(result.getPlanId(), JSON.toJSONString(result));
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
     * @param owner 规划结果的可信用户和根会话归属
     * @param planId 规划结果标识
     * @return 完整规划结果；不存在时返回null
     * @throws SystemIntervalException 存储数据无效或Redis读取失败时抛出
     */
    public ItineraryPlanningResult findById(CandidateOwner owner, String planId) {
        Assert.notNull(owner, () -> protocolError("规划结果归属不能为空"));
        Assert.notBlank(planId, () -> protocolError("planId不能为空"));
        try {
            String resultJson = planMap(owner).get(planId.trim());
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

    private RMap<String, String> planMap(CandidateOwner owner) {
        String key = KEY_PREFIX + "{" + DigestUtil.sha256Hex(JSON.toJSONBytes(owner)) + "}";
        return redissonClient.getMap(key, StringCodec.INSTANCE);
    }

    private SystemIntervalException protocolError(String message) {
        return SystemIntervalException.of(message);
    }
}
