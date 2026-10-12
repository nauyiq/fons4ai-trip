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
     * 外部平台原始状态码
     */
    private String externalStatus;

    /**
     * 金额
     */
    private String totalAmount;

}
