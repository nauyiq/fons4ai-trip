package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.common.request.BaseRequest;
import lombok.*;

/**
 * @author hongqy
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelOrderModifyRequest extends BaseRequest {

    /**
     * 用户id
     */
    private String userId;

    /**
     * 要修改的差旅单ID
     */
    private String orderId;

    /**
     * 目的地城市
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
     * 新出差事由
     */
    private String purpose;

    /**
     * 是否强制修改已审批通过的差旅单, 默认false
     */
    @Builder.Default
    private boolean force = false;


}
