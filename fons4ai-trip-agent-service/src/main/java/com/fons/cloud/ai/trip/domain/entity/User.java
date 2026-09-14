package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.UserRole;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

/**
 * @author hongqy
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@TableName("user")
public class User extends BaseEntity {

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 关联 user_profile.user_id，也是 sa-token 的 loginId
     */
    private String userId;

    /**
     * 登录账号
     */
    private String username;

    /**
     * 登录密码（生产环境应使用 BCrypt 存储）
     */
    private String password;

    /**
     * 用户真实姓名
     */
    private String realName;

    /**
     * 用户角色
     */
    private UserRole role;

}
