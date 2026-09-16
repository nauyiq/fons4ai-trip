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

    /**
     * 草稿
     */
    DRAFT("DRAFT", "草稿"),
    /**
     * 审批中
     */
    SUBMITTED("SUBMITTED", "审批中"),
    /**
     * 已通过
     */
    APPROVED("APPROVED", "已通过"),
    /**
     * 已拒绝
     */
    REJECTED("REJECTED", "已拒绝"),
    /**
     * 已完成
     */
    COMPLETED("COMPLETED", "已完成"),
    /**
     * 已取消
     */
    CANCELLED("CANCELLED", "已取消");

    /**
     * 存入数据库的值（与历史字符串兼容）
     */
    @EnumValue
    private final String code;

    /**
     * 中文显示名
     */
    private final String label;

}
