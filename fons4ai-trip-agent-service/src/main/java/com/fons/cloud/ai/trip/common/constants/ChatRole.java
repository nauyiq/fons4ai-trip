package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 对话消息角色
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ChatRole {

    USER("user"),
    AGENT("agent"),
    SYSTEM("system");

    @EnumValue
    private final String code;

}
