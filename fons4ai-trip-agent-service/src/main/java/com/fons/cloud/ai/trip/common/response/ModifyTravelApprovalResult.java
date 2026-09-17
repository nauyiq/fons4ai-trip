package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.dto.BookingSummary;

import java.util.List;

/**
 * 修改差旅单结果
 *
 * @author hongqy
 */
public record ModifyTravelApprovalResult(
        String orderId,
        String orderStatus,
        String oldApprovalId,
        boolean oldApprovalCancelled,
        String newApprovalId,
        String newApprovalStatus,
        String updatedFields,
        String message,
        List<BookingSummary> associatedBookings,
        String affectedBookingsMessage) {
}
