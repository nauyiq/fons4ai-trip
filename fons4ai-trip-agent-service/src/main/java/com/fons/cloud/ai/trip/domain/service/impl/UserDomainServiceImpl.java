package com.fons.cloud.ai.trip.domain.service.impl;

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
}
