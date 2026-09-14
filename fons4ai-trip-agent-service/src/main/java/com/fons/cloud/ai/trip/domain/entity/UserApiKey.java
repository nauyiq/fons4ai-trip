package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

/**
 * 用户第三方 API Key（加密存储）
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_api_key")
public class UserApiKey extends BaseEntity {

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 服务提供商，如 flight-manager
     */
    private String provider;

    /**
     * AES 加密后的 API Key（Base64 编码）
     */
    private String apiKeyEnc;

}
