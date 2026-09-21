package com.fons.cloud.ai.trip.infrastructure.repository;

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

    private final RedissonClient redisson;

    /**
     * 获取查询重写结果
     * @param userId
     * @param workflowId
     * @return
     */
    public String getQueryRewriteResult(String userId, String workflowId) {
        RBucket<String> bucket = getTripQueryRewriteBucket(userId, workflowId);
        return bucket.get();
    }

    /**
     * 保存查询重写结果 默认缓存2小时
     * @param workflowId
     * @param queryRewriteResult
     */
    public void saveQueryRewriteResult(String userId, String workflowId, String queryRewriteResult) {
        RBucket<String> bucket = getTripQueryRewriteBucket(userId, workflowId);
        bucket.set(queryRewriteResult, Duration.ofHours(2));
    }


    public RBucket<String> getTripQueryRewriteBucket(String userId, String workflowId) {
        return redisson.getBucket(TRIP_QUERY_REWRITING_PREFIX + userId + ":" + workflowId);
    }




}
