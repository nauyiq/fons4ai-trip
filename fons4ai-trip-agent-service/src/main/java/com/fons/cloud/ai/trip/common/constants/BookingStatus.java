package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 预订状态
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum BookingStatus {

    CREATED("CREATED"),
    PENDING_PAYMENT("PENDING_PAYMENT"),
    PAID("PAID"),
    CONFIRMED("CONFIRMED"),
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED"),
    REFUNDED("REFUNDED"),
    FAILED("FAILED");

    @EnumValue
    private final String code;

}
