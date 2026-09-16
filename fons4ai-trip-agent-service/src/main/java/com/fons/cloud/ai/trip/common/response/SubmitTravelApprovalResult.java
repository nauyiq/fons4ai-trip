package com.fons.cloud.ai.trip.common.response;

/**
 * 差旅审批提交结果，返回差旅单、审批流程及出行信息。
 * 幂等重复提交时，返回已有差旅单及审批记录的当前状态。
 *
 * @param orderId 差旅申请单ID
 * @param processInstanceId 审批流程实例ID，对应关联审批记录的主键
 * @param orderStatus 差旅申请单状态编码，取值参见 {@link com.fons.cloud.ai.trip.common.constants.OrderStatus}
 * @param approvalStatus 审批状态编码，取值参见 {@link com.fons.cloud.ai.trip.common.constants.ApprovalStatus}
 * @param submitTime 审批提交日期，当前取审批记录创建日期，格式为 {@code yyyy-MM-dd}，不包含时分秒
 * @param destination 目的地城市
 * @param departureCity 出发城市
 * @param departureDate 出发日期，格式为 {@code yyyy-MM-dd}
 * @param returnDate 返回日期，格式为 {@code yyyy-MM-dd}
 * @param purpose 出差事由
 *
 * @author hongqy
 */
public record SubmitTravelApprovalResult(
        String orderId,
        String processInstanceId,
        String orderStatus,
        String approvalStatus,
        String submitTime,
        String destination,
        String departureCity,
        String departureDate,
        String returnDate,
        String purpose) {
}
