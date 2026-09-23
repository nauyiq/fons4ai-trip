package com.fons.cloud.ai.trip.infrastructure.repository;

import com.fons.cloud.ai.trip.common.dto.IntentRecognitionResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 缓存LLM请求的意图识别结果, 防止多次调用LLM进行分析识别
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisIntentRepository {
    private static final String TRIP_QUERY_REWRITING_PREFIX = "trip:query-rewriting:";
    private static final String INTENT_RECOGNITION_REWRITING_PREFIX = "trip:intent-recognition:";

    /**
     * 分析结果保留时间，应覆盖常规人工审批和中断恢复周期。
     */
    private static final Duration ANALYSIS_RESULT_TTL = Duration.ofDays(3);

    private final RedissonClient redisson;

    /**
     * 获取查询重写结果
     * @param userId
     * @param runId
     * @return
     */
    public String getQueryRewriteResult(String userId, String runId) {
        RBucket<String> bucket = getTripQueryRewriteBucket(userId, runId);
        return bucket.get();
    }

    /**
     * 保存查询重写结果，默认保留3天。
     * @param runId
     * @param queryRewriteResult
     */
    public void saveQueryRewriteResult(String userId, String runId, String queryRewriteResult) {
        RBucket<String> bucket = getTripQueryRewriteBucket(userId, runId);
        bucket.set(queryRewriteResult, ANALYSIS_RESULT_TTL);
    }

    public IntentRecognitionResult getIntentRecognitionResult(String userId, String runId) {
        RBucket<IntentRecognitionResult> bucket = getIntentRecognitionRewriteBucket(userId, runId);
        return bucket.get();
    }

    public void saveIntentRecognitionResult(String userId, String runId, IntentRecognitionResult intentRecognitionResult) {
        RBucket<IntentRecognitionResult> bucket = getIntentRecognitionRewriteBucket(userId, runId);
        bucket.set(intentRecognitionResult, ANALYSIS_RESULT_TTL);
    }


    public RBucket<String> getTripQueryRewriteBucket(String userId, String runId) {
        return redisson.getBucket(TRIP_QUERY_REWRITING_PREFIX + userId + ":" + runId);
    }

    public RBucket<IntentRecognitionResult> getIntentRecognitionRewriteBucket(String userId, String runId) {
        return redisson.getBucket(INTENT_RECOGNITION_REWRITING_PREFIX + userId + ":" + runId);
    }


}
