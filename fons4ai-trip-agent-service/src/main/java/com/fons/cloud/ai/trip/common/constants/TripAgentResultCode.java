package com.fons.cloud.ai.trip.common.constants;

import com.fons.cloud.common.result.Result;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 差旅智能Agent业务错误码
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum TripAgentResultCode implements Result {






    ;

    private final String code;
    private final String message;

}
