package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.UserApiKey;
import com.fons.cloud.ai.trip.domain.mapper.UserApiKeyMapper;
import com.fons.cloud.ai.trip.domain.service.UserApiKeyDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class UserApiKeyDomainServiceImpl extends ServiceImpl<UserApiKeyMapper, UserApiKey> implements UserApiKeyDomainService {
}
