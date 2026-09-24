package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.request.TravelOrderCreateRequest;
import com.fons.cloud.ai.trip.infrastructure.util.IdGenerator;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

import java.util.Date;

/**
 * 差旅申请单
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("travel_order")
public class TravelOrder extends BaseEntity {

    /**
     * 差旅单ID
     */
    @TableId(value = "order_id", type = IdType.INPUT)
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
    private TravelOrderStatus status;

    /**
     * 关联审批单ID
     */
    private String approvalId;

    /**
     * 行程方案HTML的MinIO对象key
     */
    private String planHtmlUrl;

    public static TravelOrder create(TravelOrderCreateRequest request) {
        return TravelOrder.builder()
                .orderId(IdGenerator.next(IdGenerator.Prefix.ORDER))
                .userId(request.getUserId())
                .destination(request.getDestination())
                .departureCity(request.getDepartureCity())
                .departureDate(request.getDepartureDate())
                .returnDate(request.getReturnDate())
                .purpose(request.getPurpose())
                .status(TravelOrderStatus.DRAFT)
                .build();
    }

    public void submitted(String processInstanceId) {
        setApprovalId(processInstanceId);
        setStatus(TravelOrderStatus.SUBMITTED);
    }

    public void cancel() {
        setStatus(TravelOrderStatus.CANCELLED);
        setUpdated(new Date());
    }
}
