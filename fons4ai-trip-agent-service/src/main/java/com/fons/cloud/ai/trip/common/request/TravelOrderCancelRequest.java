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
public class TravelOrderCancelRequest extends BaseRequest {

    /**
     * 用户id
     */
    private String userId;

    /**
     * 差旅订单ID
     */
    private String oderId;

    /**
     * 取消原因
     */
    private String reason;

    /**
     * 是否强制取消, 默认false
     */
    @Builder.Default
    private Boolean force = false;

}
