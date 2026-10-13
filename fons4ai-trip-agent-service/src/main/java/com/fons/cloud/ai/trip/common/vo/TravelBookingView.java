package com.fons.cloud.ai.trip.common.vo;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;

/**
 * 差旅预订视图
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class TravelBookingView implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 预订ID
     */
    private String bookingId;

    /**
     * 业务类型
     */
    private String bizType;

    /**
     * 业务类型标签
     */
    private String bizTypeLabel;

    /**
     * 预订标题
     */
    private String title;

    /**
     * 状态
     */
    private String status;

    /**
     * 状态标签
     */
    private String statusLabel;

    /**
     * 金额
     */
    private String totalAmount;

    /**
     * 币种
     */
    private String currency;

    /**
     * 预订平台
     */
    private String platform;

    /**
     * 外部订单号
     */
    private String externalOrderNo;

    /**
     * 外部平台原始状态码
     */
    private String externalStatus;

    /**
     * 支付状态
     */
    private String paymentStatus;

    /**
     * 服务开始时间
     */
    private Long startTime;

    /**
     * 服务结束时间
     */
    private Long endTime;

    /**
     * 预定下单时间
     */
    private Long bookedAt;

    /**
     * 订单Url
     */
    private String orderUrl;
}
