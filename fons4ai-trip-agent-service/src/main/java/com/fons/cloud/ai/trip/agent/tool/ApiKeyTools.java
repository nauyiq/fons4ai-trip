package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.application.UserApiKeyApplicationService;
import com.fons.cloud.ai.trip.common.constants.BusinessProvider;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.response.CheckApiKeyResult;
import com.fons.cloud.ai.trip.common.response.SaveApiKeyResult;
import com.fons.cloud.ai.trip.infrastructure.config.properties.BusinessProviderConfigProperties;
import com.fons.cloud.ai.trip.infrastructure.config.properties.BusinessProviderConfigProperties.ProviderInfo;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 第三方 API Key 统一管理工具集。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApiKeyTools implements BaseTool {
    public static final List<String> TOOLS = List.of("check_tuniu_api_key", "save_tuniu_api_key");

    private final UserApiKeyApplicationService userApiKeyApplicationService;
    private final BusinessProviderConfigProperties businessProviderConfigProperties;

    /**
     * 查询运行时当前用户的凭据状态，未配置仍属于查询成功。
     */
    @Tool(name = "check_tuniu_api_key", description = "调用途牛机票、酒店、火车票服务前，检查当前用户是否配置API Key。"
            + "data.hasKey=false 时引导用户访问 data.guideUrl 获取Key，再调用 save_tuniu_api_key 保存。"
            + "hasKey=true 仅表示本地已配置，不代表Key未过期或具有远端权限；失败时不能判断为未配置。")
    public R<CheckApiKeyResult> checkTuniuApiKey(RuntimeContext context) {
        String userId = context.getUserId();
        BusinessProvider provider = BusinessProvider.TU_NIU;
        log.info("[TOOL][check_tuniu_api_key] userId={}", userId);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }

        try {
            ProviderInfo providerInfo = businessProviderConfigProperties.requireProviderInfo(provider);
            boolean hasKey = userApiKeyApplicationService.hasApiKey(userId, provider);
            String message = hasKey ? "途牛API Key已配置，远端有效性以实际服务调用为准。"
                    : "尚未配置途牛API Key，请引导用户访问guideUrl获取，并在用户提供后调用save_tuniu_api_key保存。";
            log.info("[TOOL][check_tuniu_api_key] userId={}, hasKey={}", userId, hasKey);
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message,
                    new CheckApiKeyResult(providerInfo.getName().trim(), hasKey, providerInfo.getGuideUrl().trim()));
        } catch (Exception e) {
            log.error("[TOOL][check_tuniu_api_key] 凭据状态查询失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "途牛API Key状态查询失败，不能据此判断未配置，请稍后重试或联系管理员。");
        }
    }

    /**
     * 只保存用户明确提供的Key，已有记录覆盖；不记录或回显明文、密文及Key片段。
     */
    @Tool(name = "save_tuniu_api_key", description = "用户明确提供途牛API Key后，加密保存到当前用户的凭据记录，已有Key会被覆盖。"
            + "去除首尾空格，按提供商配置检查前缀；不进行远端认证。SUCCESS 表示保存完成，不代表Key有效或预订成功，不向用户回显Key。")
    public R<SaveApiKeyResult> saveTuniuApiKey(RuntimeContext context,
                                               @ToolParam(name = "api_key", description = "用户明确提供的完整途牛API Key，前缀以提供商配置为准；不能包含内部空白，不可编造") String apiKey) {
        String userId = context.getUserId();
        BusinessProvider provider = BusinessProvider.TU_NIU;
        log.info("[TOOL][save_tuniu_api_key] userId={}", userId);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        if (StringUtils.isBlank(apiKey)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "api_key 不能为空，请提供途牛API Key");
        }

        try {
            ProviderInfo providerInfo = businessProviderConfigProperties.requireProviderInfo(provider);
            userApiKeyApplicationService.saveApiKey(userId, provider, apiKey);
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "途牛API Key已加密保存，远端有效性以实际服务调用为准。",
                    new SaveApiKeyResult(providerInfo.getName().trim(), true));
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][save_tuniu_api_key] 凭据参数无效，userId={}, code={}", userId, e.getCode());
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), e.getMessage());
        } catch (Exception e) {
            // 数据库异常可能包含绑定值，不输出异常对象，避免将凭据密文写入日志。
            log.error("[TOOL][save_tuniu_api_key] 凭据保存失败，userId={}, exceptionType={}", userId, e.getClass().getSimpleName());
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "途牛API Key保存失败，请稍后重试或联系管理员。");
        }
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }
}
