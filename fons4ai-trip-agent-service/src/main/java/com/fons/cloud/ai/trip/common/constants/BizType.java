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
public enum BizType {

    FLIGHT("FLIGHT"),
    HOTEL("HOTEL"),
    TRAIN("TRAIN"),
    TICKET("TICKET"),
    CRUISE("CRUISE"),
    VACATION("VACATION");

    @EnumValue
    private final String code;

}
