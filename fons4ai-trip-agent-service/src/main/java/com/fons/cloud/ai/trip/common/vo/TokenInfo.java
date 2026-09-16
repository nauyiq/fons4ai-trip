package com.fons.cloud.ai.trip.common.vo;

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
public class TokenInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String token;
    private String tokenName;

    public static TokenInfo of(String token) {
        TokenInfo response = new TokenInfo();
        response.setToken(token);
        response.setTokenName(StpUtil.getTokenName());
        return response;
    }
}
