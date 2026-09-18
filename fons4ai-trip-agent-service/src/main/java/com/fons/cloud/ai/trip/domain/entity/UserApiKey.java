package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

/**
 * 用户第三方 API Key（加密存储）
 *
 * @deprecated 企业统一管理供应商凭据，个人API Key能力仅保留兼容，新业务不再使用。
 * @author hongqy
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_api_key")
@Deprecated
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
