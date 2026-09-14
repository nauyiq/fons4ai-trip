package com.fons.cloud.ai.trip.common.response;

import cn.dev33.satoken.stp.StpUtil;
import lombok.Getter;
import lombok.Setter;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author hongqy
 */
@Getter
@Setter
public class TokenResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String token;
    private String tokenName;

    public static TokenResponse of(String token) {
        TokenResponse response = new TokenResponse();
        response.setToken(token);
        response.setTokenName(StpUtil.getTokenName());
        return response;
    }
}
