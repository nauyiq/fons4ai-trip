package com.fons.cloud.ai.trip.infrastructure.repository;

import cn.hutool.core.lang.Assert;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.alibaba.fastjson2.JSONObject;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.dto.CandidatePoolSnapshot;
import com.fons.cloud.ai.trip.common.dto.HotelCandidate;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateGroups;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateScope;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateSnapshot;
import com.fons.cloud.ai.trip.common.dto.TransportCandidate;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RLock;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 按用户、差旅单和行程条件分组暂存候选；每条候选自供应商获取时间起最多保留30分钟。
 * 同一分组内按客户端获取时间决定覆盖顺序，不推断供应商生成报价的先后。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryCandidateRepository {

    private static final String KEY_PREFIX = "trip:itinerary:candidates:v2:";
    private static final Duration CANDIDATE_TTL = Duration.ofMinutes(30);
    private final RedissonClient redissonClient;

    /**
     * 保存当前页交通候选；相同ID仅在获取时间更新时覆盖。
     *
     * @param scope 机票或火车分组，必须与候选交通类型一致
     * @param candidates 当前页的多个交通候选，允许为空；同一ID保留本页最后一条
     * @param searchedAt 供应商结果的获取时间，决定每条候选的过期时间
     * @throws SystemIntervalException 候选或分组不匹配，或Redis保存失败时抛出
     */
    public void saveTransportCandidates(ItineraryCandidateScope scope, List<TransportCandidate> candidates, OffsetDateTime searchedAt) {
        Assert.isTrue(scope != null && (scope.type() == BookingType.FLIGHT || scope.type() == BookingType.TRAIN),
                () -> protocolError("交通候选需要机票或火车分组"));
        Assert.notNull(candidates, () -> protocolError("交通候选列表不能为空"));
        for (TransportCandidate candidate : candidates) {
            validateTransportCandidate(candidate, scope);
        }
        save(poolKey(scope), encodeCandidates(candidates, TransportCandidate::candidateId), searchedAt);
    }

    /**
     * 保存当前页酒店候选，不将不同入住日期、人数或儿童年龄的报价混入同一分组。
     *
     * @param scope 酒店分组，包含城市、入住日期、成人数及儿童年龄
     * @param candidates 当前页的多个酒店候选，允许为空；同一ID保留本页最后一条
     * @param searchedAt 供应商结果的获取时间，决定每条候选的过期时间
     * @throws SystemIntervalException 候选或分组不匹配，或Redis保存失败时抛出
     */
    public void saveHotelCandidates(ItineraryCandidateScope scope, List<HotelCandidate> candidates, OffsetDateTime searchedAt) {
        Assert.isTrue(scope != null && scope.type() == BookingType.HOTEL,
                () -> protocolError("酒店候选需要酒店分组"));
        Assert.notNull(candidates, () -> protocolError("酒店候选列表不能为空"));
        for (HotelCandidate candidate : candidates) {
            validateHotelCandidate(candidate, scope);
        }
        save(poolKey(scope), encodeCandidates(candidates, HotelCandidate::candidateId), searchedAt);
    }

    /**
     * 批量读取应用层指定的五个候选分组，不在存储层推导路线和入住日期。
     *
     * @param groups 去程机票、火车，返程机票、火车及酒店分组，必须属于同一用户和差旅单
     * @return 未过期候选组成的固定Java快照，不进行业务过滤或打分
     * @throws SystemIntervalException Redis读取失败或候选存储协议无效时抛出
     */
    public ItineraryCandidateSnapshot loadSnapshot(ItineraryCandidateGroups groups) {
        Assert.notNull(groups, () -> protocolError("规划候选分组不能为空"));
        RBatch batch = redissonClient.createBatch();
        RFuture<Map<String, String>> outboundFlights = readAll(batch, groups.outboundFlights());
        RFuture<Map<String, String>> outboundTrains = readAll(batch, groups.outboundTrains());
        RFuture<Map<String, String>> inboundFlights = readAll(batch, groups.inboundFlights());
        RFuture<Map<String, String>> inboundTrains = readAll(batch, groups.inboundTrains());
        RFuture<Map<String, String>> hotels = readAll(batch, groups.hotels());
        try {
            batch.execute();
        } catch (RedisException e) {
            log.warn("[ItineraryCandidateStore] 读取候选快照失败，exceptionType={}", e.getClass().getSimpleName());
            throw SystemIntervalException.of("行程候选快照读取失败", e);
        }
        try {
            return new ItineraryCandidateSnapshot(OffsetDateTime.now(ZoneOffset.UTC),
                    decodePool(groups.outboundFlights(), completedValue(outboundFlights), TransportCandidate.class,
                            this::validateTransportCandidate, TransportCandidate::candidateId),
                    decodePool(groups.outboundTrains(), completedValue(outboundTrains), TransportCandidate.class,
                            this::validateTransportCandidate, TransportCandidate::candidateId),
                    decodePool(groups.inboundFlights(), completedValue(inboundFlights), TransportCandidate.class,
                            this::validateTransportCandidate, TransportCandidate::candidateId),
                    decodePool(groups.inboundTrains(), completedValue(inboundTrains), TransportCandidate.class,
                            this::validateTransportCandidate, TransportCandidate::candidateId),
                    decodePool(groups.hotels(), completedValue(hotels), HotelCandidate.class,
                            this::validateHotelCandidate, HotelCandidate::candidateId));
        } catch (JSONException e) {
            throw SystemIntervalException.of("候选存储JSON格式无效", e);
        }
    }

    private RFuture<Map<String, String>> readAll(RBatch batch, ItineraryCandidateScope scope) {
        return batch.<String, String>getMapCache(poolKey(scope), StringCodec.INSTANCE).readAllMapAsync();
    }

    private <T> T completedValue(RFuture<T> future) {
        try {
            return future.toCompletableFuture().join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof CancellationException cancellation) {
                throw cancellation;
            }
            throw SystemIntervalException.of("行程候选快照读取失败", cause);
        }
    }

    /** 同分组的读取比较与写入在同一把锁内，避免获取时间较早的结果覆盖较晚的结果。 */
    private void save(String poolKey, Map<String, String> candidates, OffsetDateTime searchedAt) {
        Assert.notNull(searchedAt, () -> protocolError("候选供应商获取时间不能为空"));
        if (candidates.isEmpty()) {
            return;
        }
        try {
            RLock lock = redissonClient.getLock(poolKey + ":lock");
            try {
                lock.lockInterruptibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                CancellationException cancellation = new CancellationException("候选保存已中断");
                cancellation.initCause(e);
                throw cancellation;
            }
            try {
                RMapCache<String, String> candidateMap = redissonClient.getMapCache(poolKey, StringCodec.INSTANCE);
                for (Map.Entry<String, String> entry : candidates.entrySet()) {
                    String previous = candidateMap.get(entry.getKey());
                    if (previous != null && !searchedAt.isAfter(readSearchedAt(JSON.parseObject(previous)))) {
                        continue;
                    }
                    // 逐条按供应商获取时间计算剩余期限，不能因排队或补搜延长旧报价寿命。
                    long remainingMillis = Math.min(CANDIDATE_TTL.toMillis(),
                            Duration.between(Instant.now(), searchedAt.toInstant().plus(CANDIDATE_TTL)).toMillis());
                    if (remainingMillis <= 0) {
                        continue;
                    }
                    JSONObject stored = new JSONObject();
                    stored.put("searchedAt", searchedAt.toString());
                    stored.put("candidate", JSON.parseObject(entry.getValue()));
                    candidateMap.put(entry.getKey(), stored.toJSONString(), remainingMillis, TimeUnit.MILLISECONDS);
                }
            } finally {
                lock.unlock();
            }
        } catch (RedisException e) {
            log.warn("[ItineraryCandidateStore] 保存候选失败，exceptionType={}", e.getClass().getSimpleName());
            throw SystemIntervalException.of("搜索候选保存失败，请重新搜索后再规划", e);
        } catch (JSONException e) {
            throw SystemIntervalException.of("候选存储JSON格式无效", e);
        }
    }

    private <T> Map<String, String> encodeCandidates(List<T> candidates, Function<T, String> idReader) {
        try {
            Map<String, String> result = new LinkedHashMap<>();
            for (T candidate : candidates) {
                result.put(idReader.apply(candidate), JSON.toJSONString(candidate));
            }
            return result;
        } catch (JSONException e) {
            throw SystemIntervalException.of("搜索候选序列化失败", e);
        }
    }

    private <T> CandidatePoolSnapshot<T> decodePool(ItineraryCandidateScope scope, Object rawCandidates,
                                                    Class<T> candidateType,
                                                    BiConsumer<T, ItineraryCandidateScope> validator,
                                                    Function<T, String> idReader) {
        Assert.isTrue(rawCandidates instanceof Map<?, ?>, () -> protocolError("候选Hash格式无效"));
        Map<String, T> candidatesById = new TreeMap<>();
        Map<String, OffsetDateTime> searchedAtById = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) rawCandidates).entrySet()) {
            Assert.isTrue(entry.getKey() instanceof String && entry.getValue() instanceof String,
                    () -> protocolError("候选Hash字段和值必须是字符串"));
            String candidateId = (String) entry.getKey();
            JSONObject stored = JSON.parseObject((String) entry.getValue());
            OffsetDateTime searchedAt = readSearchedAt(stored);
            // 按获取时间再次过滤，处理读取与Redis过期之间的边界竞态。
            if (!searchedAt.toInstant().plus(CANDIDATE_TTL).isAfter(Instant.now())) {
                continue;
            }
            Assert.isTrue(stored.getJSONObject("candidate") != null, () -> protocolError("候选存储内容无效"));
            T candidate = stored.getJSONObject("candidate").toJavaObject(candidateType);
            validator.accept(candidate, scope);
            Assert.isTrue(candidateId.equals(idReader.apply(candidate)),
                    () -> protocolError("候选ID与Hash字段不一致"));
            candidatesById.put(candidateId, candidate);
            searchedAtById.put(candidateId, searchedAt);
        }
        return new CandidatePoolSnapshot<>(scope, List.copyOf(candidatesById.values()), searchedAtById);
    }

    private OffsetDateTime readSearchedAt(JSONObject stored) {
        Assert.isTrue(stored != null && StringUtils.isNotBlank(stored.getString("searchedAt")),
                () -> protocolError("候选供应商获取时间无效"));
        try {
            return OffsetDateTime.parse(stored.getString("searchedAt"));
        } catch (DateTimeParseException e) {
            throw SystemIntervalException.of("候选供应商获取时间无效", e);
        }
    }

    private String poolKey(ItineraryCandidateScope scope) {
        Assert.notNull(scope, () -> protocolError("候选分组不能为空"));
        return KEY_PREFIX + "{" + DigestUtil.sha256Hex(JSON.toJSONBytes(List.of(scope.userId(), scope.orderId()))) + "}:"
                + DigestUtil.sha256Hex(JSON.toJSONBytes(scope));
    }

    private void validateTransportCandidate(TransportCandidate candidate, ItineraryCandidateScope scope) {
        Assert.isTrue(candidate != null && candidate.type() == scope.type() && StringUtils.isNotBlank(candidate.code())
                        && candidate.source() != null && candidate.source().provider() != null && candidate.price() != null,
                () -> protocolError("交通候选类型或必要字段无效"));
        validateCandidateId(candidate.candidateId());
    }

    private void validateHotelCandidate(HotelCandidate candidate, ItineraryCandidateScope scope) {
        Assert.isTrue(candidate != null && StringUtils.isNotBlank(candidate.name()) && candidate.source() != null
                        && candidate.source().provider() != null && candidate.price() != null
                        && scope.startDate().equals(candidate.checkInDate()) && scope.endDate().equals(candidate.checkOutDate())
                        && scope.adultCount() == candidate.adultCount() && scope.childAges().size() == candidate.childCount(),
                () -> protocolError("酒店候选的入住条件或必要字段与分组不一致"));
        validateCandidateId(candidate.candidateId());
    }

    private void validateCandidateId(String id) {
        Assert.isTrue(StringUtils.isNotBlank(id) && id.equals(id.trim()),
                () -> protocolError("候选ID不能为空或包含首尾空格"));
    }

    private SystemIntervalException protocolError(String message) {
        return SystemIntervalException.of(message);
    }
}
