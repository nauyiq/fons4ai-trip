package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.User;
import com.fons.cloud.ai.trip.domain.mapper.UserMapper;
import com.fons.cloud.ai.trip.domain.service.UserDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class UserDomainServiceImpl extends ServiceImpl<UserMapper, User> implements UserDomainService {

    @Override
    public User findByUsername(String username) {
        return this.getOne(Wrappers.lambdaQuery(User.class).eq(User::getUsername, username.trim()));
    }

    @Override
    public User findByUserId(String userId) {
        return this.getOne(Wrappers.lambdaQuery(User.class).eq(User::getUserId, userId.trim()));
    }


}
