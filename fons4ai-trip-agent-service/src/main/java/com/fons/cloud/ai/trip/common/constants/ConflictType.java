package com.fons.cloud.ai.trip.common.constants;

/**
 * 行程冲突类型。
 *
 * @author hongqy
 */
public enum ConflictType {
    /**
     * 时间重叠且出发城市、目的地分别相同，存在重复申请风险。
     */
    TIME_OVERLAP_SAME_CITY,
    /**
     * 时间重叠且路线不同，需要核实或调整行程。
     */
    TIME_OVERLAP_DIFF_CITY,
    /**
     * 同日跨城衔接存在时间风险。
     */
    TRANSIT_TOO_TIGHT,
    /**
     * 次日跨城衔接的估算时长超过阈值。
     */
    DISCONNECTED_ROUTE
}
