package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ConflictSeverity;
import com.fons.cloud.ai.trip.common.constants.ConflictType;

import java.util.List;

/**
 * 行程冲突检查报告。工具执行成功不代表没有冲突，需读取 hasConflict。
 * 交通时长为估算值，不代表真实班次可达或已验证的交通方案。
 *
 * @param hasConflict    是否存在任意等级的冲突
 * @param totalConflicts 冲突明细数量
 * @param conflicts      冲突明细，按 HIGH、MEDIUM、LOW 排序
 * @param summary        检查摘要
 * @author hongqy
 */
public record CheckTravelOrderConflictsResult(
        boolean hasConflict,
        int totalConflicts,
        List<ConflictItem> conflicts,
        String summary) {

    /**
     * 冲突对应的已有申请及调整建议。
     */
    public record ConflictItem(
            ConflictType type,
            ConflictSeverity severity,
            String orderId,
            String orderSummary,
            String description,
            String suggestion) {
    }
}
