package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.OrderStatus;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

/**
 * 差旅申请单
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@TableName("travel_order")
public class TravelOrder extends BaseEntity {

    /**
     * 差旅单ID
     */
    @TableId
    private String orderId;

    /**
     * 申请人ID
     */
    private String userId;

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
     * 状态: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED
     */
    private OrderStatus status;

    /**
     * 关联审批单ID
     */
    private String approvalId;

    /**
     * 行程方案HTML的MinIO对象key
     */
    private String planHtmlUrl;

}
