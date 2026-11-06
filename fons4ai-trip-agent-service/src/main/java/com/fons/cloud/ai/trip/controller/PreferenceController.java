package com.fons.cloud.ai.trip.controller;

import com.fons.cloud.ai.trip.application.user.PreferenceApplicationService;
import com.fons.cloud.ai.trip.common.dto.preference.PreferenceCatalog;
import com.fons.cloud.ai.trip.common.dto.preference.PreferenceData.*;
import com.fons.cloud.auth.satoken.api.SaTokenAuthTemplate;
import com.fons.cloud.common.result.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.fons.cloud.ai.trip.common.constants.TripAgentResultCode.PREFERENCE_INVALID;

/** 偏好页面只读取业务数据；不调用 LLM 解析或合并原生记忆。 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/preferences")
@RequiredArgsConstructor
public class PreferenceController {
    private final PreferenceApplicationService preferences;
    private final SaTokenAuthTemplate authentication;

    @GetMapping("/options")
    public R<List<PreferenceCatalog.Category>> options() {
        // 同时检查登录和功能开关，关闭时不提供可提交的旧表单。
        return R.success(preferences.options(authentication.getCurrentLoginIdAsString()));
    }

    @GetMapping
    public R<Snapshot> query() {
        return R.success(preferences.query(authentication.getCurrentLoginIdAsString()));
    }

    @PostMapping
    public R<Snapshot> save(@RequestBody SaveRequest request) {
        return R.success(preferences.save(authentication.getCurrentLoginIdAsString(), request));
    }

    /** 枚举或 JSON 绑定错误仍是偏好参数错误，避免被全局兜底当作系统故障。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleInvalidRequest(HttpMessageNotReadableException error) {
        return R.failed(PREFERENCE_INVALID);
    }
}
