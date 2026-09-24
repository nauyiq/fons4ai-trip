package com.fons.cloud.ai.trip.infrastructure.util;

import cn.hutool.core.date.SystemClock;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author hongqy
 */
public class IdGenerator {

    /** 进程内自增序列（随机起点），取低 24 位作为 6 位十六进制后缀 */
    private static final AtomicLong SEQUENCE = new AtomicLong(ThreadLocalRandom.current().nextLong());

    private IdGenerator() {
    }

    /**
     * 生成带前缀的唯一ID，如 {@code to_1753948800000_a3f9c2}。
     *
     * @param prefix 业务前缀（如 to_ / pi_）
     */
    private static String next(String prefix) {
        return prefix + SystemClock.now() + String.format("%06x", SEQUENCE.getAndIncrement() & 0xFFFFFF);
    }


    public static String next(Prefix prefix) {
        return next(prefix.getPrefix());
    }

    @Getter
    @AllArgsConstructor
    public enum Prefix {
        APPROVAL("TRA"),
        CONVERSATION("TRC"),
        TRACE("TRT"),
        MESSAGE("TRM"),
        USER("TRU"),
        ORDER("TRO"),


        ;
        private final String prefix;
    }

}
