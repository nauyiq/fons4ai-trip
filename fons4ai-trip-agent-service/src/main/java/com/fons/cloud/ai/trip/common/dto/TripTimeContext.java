package com.fons.cloud.ai.trip.common.dto;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;

/**
 * 单轮请求的日期基准，父子 Agent 共享；不作为会话历史或长期记忆持久化。
 * 星期由日期派生，同一轮内不因跨午夜而重新采集日期。
 */
public record TripTimeContext(LocalDate currentDate, ZoneId zoneId) {

    public TripTimeContext {
        Objects.requireNonNull(currentDate, "currentDate must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
    }
}
