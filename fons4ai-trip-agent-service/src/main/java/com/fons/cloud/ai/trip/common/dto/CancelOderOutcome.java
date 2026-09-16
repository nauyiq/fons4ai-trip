package com.fons.cloud.ai.trip.common.dto;

/**
 * 取消订单结果
 * @param orderCancelled      订单是否取消
 * @param approvalCancelled   审批是否取消
 * @param cancelledApprovalId 取消的审批ID
 * @author hongqy
 */
public record CancelOderOutcome(
        boolean orderCancelled,
        boolean approvalCancelled,
        String cancelledApprovalId) {
}
