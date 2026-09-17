package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 行程冲突严重程度，仅表示风险，不代表工具执行失败或自动阻断。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ConflictSeverity {
    HIGH(0),
    MEDIUM(1),
    LOW(2);

    /**
     * 排序权重，数值越小越严重。
     */
    private final int rank;
}
