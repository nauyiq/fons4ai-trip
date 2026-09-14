package com.fons.cloud.ai.trip.application;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.constants.UserRole;
import com.fons.cloud.ai.trip.common.request.LoginRequest;
import com.fons.cloud.ai.trip.common.response.UserInfo;
import com.fons.cloud.ai.trip.domain.entity.User;
import com.fons.cloud.ai.trip.domain.service.UserDomainService;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务应用层, 负责用户认证业务逻辑编排
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthApplicationService {
    private final UserDomainService userDomainService;
    private final SaTokenAuthTemplate saTokenAuthTemplate;
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /**
     * 登录请求
     * @param request
     * @return
     */
    public String login(LoginRequest request) {
        User user = userDomainService.findByUsername(request.getUsername());

        Assert.notNull(user, () -> BusinessRuntimeException.of(TripAgentResultCode.USER_NOT_EXIST));
        Assert.isTrue(passwordEncoder.matches(request.getPassword(), user.getPassword()), () -> BusinessRuntimeException.of(TripAgentResultCode.PASSWORD_INCORRECT));

        saTokenAuthTemplate.login(user.getUserId());
        return saTokenAuthTemplate.getTokenValue();
    }

    /**
     * 退出登录
     */
    public void logout() {
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        saTokenAuthTemplate.logout(userId);
        log.info("User logout succeeded, userId: {}", userId);
    }

    /**
     * 获取登录用户信息
     * @return
     */
    public UserInfo getLoginInfo() {
        String userId = saTokenAuthTemplate.getCurrentLoginIdAsString();
        User user = userDomainService.findByUserId(userId);
        Assert.notNull(user, () -> BusinessRuntimeException.of(TripAgentResultCode.LOGIN_EXPIRED));

        return UserInfo.builder()
                .userId(user.getUserId())
                .username(user.getUsername())
                .realName(user.getRealName())
                .admin(user.getRole() == UserRole.ADMIN)
                .build();
    }
}
