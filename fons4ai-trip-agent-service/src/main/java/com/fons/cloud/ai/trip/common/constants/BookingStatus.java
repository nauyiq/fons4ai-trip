package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import jodd.util.StringUtil;
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

    /**
     * 已创建
     */
    CREATED("CREATED", "已创建"),
    /**
     * 待支付
     */
    PENDING_PAYMENT("PENDING_PAYMENT", "待支付"),
    /**
     * 已支付
     */
    PAID("PAID", "已支付"),
    /**
     * 已确认
     */
    CONFIRMED("CONFIRMED", "已确认"),
    /**
     * 已完成
     */
    COMPLETED("COMPLETED", "已完成"),
    /**
     * 已取消
     */
    CANCELLED("CANCELLED", "已取消"),
    /**
     * 已退款
     */
    REFUNDED("REFUNDED", "已退款"),
    /**
     * 预订失败
     */
    FAILED("FAILED", "预订失败");

    /**
     * 存入数据库的值
     */
    @EnumValue
    private final String code;

    /**
     * 中文显示名
     */
    private final String label;

    public static BookingStatus of(String status) {
        if (StringUtil.isBlank(status)) {
            return null;
        }
        for (BookingStatus bookingStatus : BookingStatus.values()) {
            if (bookingStatus.code.equals(status)) {
                return bookingStatus;
            }
        }
        return null;
    }
}
