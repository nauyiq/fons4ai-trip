package com.fons.cloud.ai.trip.common.request;

import com.fons.cloud.common.request.BaseRequest;
import lombok.*;

import java.util.Date;

/**
 * @author hongqy
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TravelOrderCreateRequest extends BaseRequest {

    /**
     * 用户id
     */
    private String userId;

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
     * 出差事由
     */
    private String purpose;



}
