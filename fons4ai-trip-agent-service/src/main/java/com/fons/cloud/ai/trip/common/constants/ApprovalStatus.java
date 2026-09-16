package com.fons.cloud.ai.trip.common.constants;

import com.baomidou.mybatisplus.annotation.EnumValue;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审批状态
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ApprovalStatus {

    /**
     * 待审批
     */
    PENDING("PENDING", "待审批"),
    /**
     * 已通过
     */
    APPROVED("APPROVED", "已通过"),
    /**
     * 已拒绝
     */
    REJECTED("REJECTED", "已拒绝"),
    /**
     * 已撤销
     */
    CANCELLED("CANCELLED", "已撤销");

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
