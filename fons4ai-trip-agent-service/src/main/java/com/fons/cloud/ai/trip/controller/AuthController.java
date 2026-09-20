package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.trip.application.user.AuthApplicationService;
import com.fons.cloud.ai.trip.common.request.LoginRequest;
import com.fons.cloud.ai.trip.common.vo.TokenInfo;
import com.fons.cloud.ai.trip.common.vo.UserInfo;
import com.fons.cloud.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 登录认证控制器
 * @author hongqy
 */
@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AuthController {
    private final AuthApplicationService authApplicationService;

    /**
     * 用户登录接口， 校验密码
     * @param request
     * @return
     */
    @PostMapping("/login")
    public R<TokenInfo> login(@Valid LoginRequest request) {
        String token = authApplicationService.login(request);
        return R.ok(TokenInfo.of(token));
    }

    /**
     * 用户退出登录
     * @return
     */
    @PostMapping("/logout")
    public R<Void> logout() {
        authApplicationService.logout();
        return R.ok();
    }

    /**
     * 获取当前登录用户信息
     * @return
     */
    @GetMapping("/info")
    public R<UserInfo> getLoginInfo() {
        UserInfo userInfo = authApplicationService.getLoginInfo();
        return R.ok(userInfo);
    }

}
