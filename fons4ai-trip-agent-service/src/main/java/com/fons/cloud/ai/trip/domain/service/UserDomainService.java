package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.User;

/**
 * @author hongqy
 */
public interface UserDomainService extends IService<User> {

    /**
     * 根据用户名查找用户
     * @param username
     * @return
     */
    User findByUsername(String username);

    /**
     * 根据用户ID查找用户
     * @param userId
     * @return
     */
    User findByUserId(String userId);
}
