package com.fons.cloud.ai.trip.common.vo;

import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;
import com.fons.cloud.ai.trip.domain.entity.BookingRecord;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.infrastructure.matcher.InternationalMatcher;
import lombok.*;
import org.apache.commons.collections4.CollectionUtils;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 差旅订单视图
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class TravelOrderView implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 订单ID
     */
    private String orderId;

    /**
     * 目的地
     */
    private String destination;

    /**
     * 出发城市
     */
    private String departureCity;

    /**
     * 出发日期
     */
    private String departureDate;

    /**
     * 返回日期
     */
    private String returnDate;

    /**
     * 出差事由
     */
    private String purpose;

    /**
     * 差旅单状态
     */
    private String status;

    /**
     * 差旅单状态描述
     */
    private String statusLabel;

    /**
     * 创建时间
     */
    private Long created;

    /**
     * 更新时间
     */
    private Long updated;

    /**
     * 是否是国际航班
     */
    private Boolean international;

    /**
     * 行程方案的HTML URL
     */
    private String planHtmlUrl;

    /**
     * 审批记录ID
     */
    private String approvalId;

    /**
     * 审批状态
     */
    private String approvalStatus;

    /**
     * 审批状态描述
     */
    private String approvalStatusLabel;

    /**
     * 审批备注
     */
    private String approvalRemark;

    /**
     * 审批记录提交时间
     */
    private Long approvalSubmitTime;

    /**
     * 审批记录更新时间
     */
    private Long approvalUpdateTime;

    /**
     * 预订记录
     */
    private List<TravelBookingView> bookings;

    /**
     * 预订数目
     */
    private Integer bookingCount;


    public static TravelOrderView of(TravelOrder order, ApprovalRecord approvalRecord, List<BookingRecord> records) {
        TravelOrderViewBuilder builder = TravelOrderView.builder();
        // 订单基础信息
        builder.orderId(order.getOrderId());
        builder.destination(order.getDestination());
        builder.departureCity(order.getDepartureCity());
        builder.departureDate(order.getDepartureDate());
        builder.returnDate(order.getReturnDate());
        builder.purpose(order.getPurpose());
        builder.status(order.getStatus() == null ? null : order.getStatus().getCode());
        builder.statusLabel(order.getStatus() == null ? null : order.getStatus().getLabel());
        builder.created(order.getCreated().getTime());
        builder.updated(order.getUpdated().getTime());
        builder.international(InternationalMatcher.isInternational(order.getDestination()));
        builder.planHtmlUrl(order.getPlanHtmlUrl());

        // 审批记录信息
        if (approvalRecord != null) {
            builder.approvalId(approvalRecord.getProcessInstanceId());
            builder.approvalStatus(approvalRecord.getStatus() == null ? null : approvalRecord.getStatus().getCode());
            builder.approvalStatusLabel(approvalRecord.getStatus() == null ? null : approvalRecord.getStatus().getLabel());
            builder.approvalRemark(approvalRecord.getRemark());
            builder.approvalSubmitTime(approvalRecord.getCreated().getTime());
            builder.approvalUpdateTime(approvalRecord.getUpdated().getTime());
        }

        // 预订记录信息
        if (CollectionUtils.isNotEmpty(records)) {
            List<TravelBookingView> bookingViews = records.stream().map(booking -> {
                return TravelBookingView.builder()
                        .bookingId(booking.getBookingId())
                        .bizType(booking.getBizType() == null ? null : booking.getBizType().getCode())
                        .bizTypeLabel(booking.getBizType() == null ? null : booking.getBizType().getLabel())
                        .title(booking.getTitle())
                        .status(booking.getStatus() == null ? null : booking.getStatus().getCode())
                        .statusLabel(booking.getStatus() == null ? null : booking.getStatus().getLabel())
                        .totalAmount(booking.getTotalAmount() == null ? null : booking.getTotalAmount().toPlainString())
                        .currency(booking.getCurrency())
                        .platform(booking.getPlatform())
                        .externalOrderNo(booking.getExternalOrderNo())
                        .externalStatus(booking.getExternalStatus())
                        .paymentStatus(booking.getPaymentStatus().getCode())
                        .startTime(booking.getStartTime() == null ? null : booking.getStartTime().getTime())
                        .endTime(booking.getEndTime() == null ? null : booking.getEndTime().getTime())
                        .bookedAt(booking.getBookedAt() == null ? null : booking.getBookedAt().getTime())
                        // TODO
//                        .orderUrl()
                        .build();
            }).toList();
            builder.bookings(bookingViews);
            builder.bookingCount(bookingViews.size());
        }
        return builder.build();
    }
}
