package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum Gender {


    MALE("M"),

    FEMALE("F");


    ;
    @EnumValue
    private final String code;

}
