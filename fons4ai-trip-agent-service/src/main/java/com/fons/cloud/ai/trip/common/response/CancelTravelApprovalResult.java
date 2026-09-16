package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.dto.BookingSummary;

import java.util.List;

/**
 * 取消差旅单结果
 * @author hongqy
 */
public record CancelTravelApprovalResult(
        String orderId,
        String orderStatus,
        boolean approvalCancelled,
        String cancelledApprovalId,
        String reason,
        List<BookingSummary> associatedBookings,
        String affectedBookingsMessage) {
}
