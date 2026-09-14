package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付状态
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum PaymentStatus {

    UNPAID("UNPAID"),
    PAID("PAID"),
    REFUNDED("REFUNDED");

    @EnumValue
    private final String code;

}
