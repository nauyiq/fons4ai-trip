package com.fons.cloud.ai.trip.common.constants;

/**
 * 当前会话中行程规划修复流程的状态。
 *
 * @author hongqy
 */
public enum ItineraryRepairStatus {

    AWAITING_REVIEW,
    REPLAN_ALLOWED,
    RETRY_REVIEW,
    INPUT_REQUIRED,
    CLOSED
}
