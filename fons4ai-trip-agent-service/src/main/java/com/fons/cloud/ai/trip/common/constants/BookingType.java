package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 预订业务类型
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum BookingType {

    /**
     * 机票
     */
    FLIGHT("FLIGHT", "机票"),
    /**
     * 酒店
     */
    HOTEL("HOTEL", "酒店"),
    /**
     * 火车票
     */
    TRAIN("TRAIN", "火车票");
    /**
     * 存入数据库的值
     */
    @EnumValue
    private final String code;

    /**
     * 中文显示名
     */
    private final String label;

    public static BookingType of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (BookingType t : values()) {
            if (t.code.equalsIgnoreCase(code)) {
                return t;
            }
        }
        return null;
    }

}
