package com.fons.cloud.ai.trip.infrastructure.util;

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
    public static String next(String prefix) {
        return prefix + System.currentTimeMillis() + "_"
                + String.format("%06x", SEQUENCE.getAndIncrement() & 0xFFFFFF);
    }

}
