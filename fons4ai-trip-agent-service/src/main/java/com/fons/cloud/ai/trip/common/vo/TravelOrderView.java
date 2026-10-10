package com.fons.cloud.ai.trip.common.vo;

import lombok.*;

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



}
