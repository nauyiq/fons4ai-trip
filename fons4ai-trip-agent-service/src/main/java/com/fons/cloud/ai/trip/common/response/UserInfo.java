package com.fons.cloud.ai.trip.common.response;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;

/**
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@AllArgsConstructor
@NoArgsConstructor
public class UserInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户姓名
     */
    private String username;

    /**
     * 用户真实姓名
     */
    private String realName;

    /**
     * 是否是管理员
     */
    private boolean admin;

}
