package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 聊天消息内容类型
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ChatMessageContentType {

    /**
     * 文本
     */
    TEXT("TEXT"),

    /**
     * 图片
     */
    IMAGE("IMAGE"),

    /**
     * 语音
     */
    VOICE("VOICE");

    /**
     * 数据库存储值，与枚举名称保持一致，供 MybatisEnumTypeHandler 读写消息类型。
     */
    @EnumValue
    private final String code;
}
