package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 差旅申请单状态
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum OrderStatus {

    DRAFT("DRAFT"),
    SUBMITTED("SUBMITTED"),
    APPROVED("APPROVED"),
    REJECTED("REJECTED"),
    COMPLETED("COMPLETED"),
    CANCELLED("CANCELLED");

    @EnumValue
    private final String code;

}
