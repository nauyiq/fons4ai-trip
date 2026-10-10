package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.VisaQuickCheckResult;
import com.fons.cloud.ai.trip.common.dto.VisaRequirementResult;
import com.fons.cloud.ai.trip.infrastructure.client.OriznVisaClient;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.R;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 签证查询工具；护照签发国和目的地由用户信息确定，不从出发城市推断国籍。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VisaTools implements BaseTool {

    public static final List<String> TOOLS = List.of("quick_visa_check", "check_visa_requirement");
    private static final Set<String> LANGUAGES = Set.of("en", "fr", "es", "pt", "de", "it", "ja", "ko",
            "zh", "ru", "ar", "hi", "th", "vi", "tl");

    private final OriznVisaClient oriznVisaClient;

    /**
     * 快速判断签证类型；不包含申请材料与办理过程。
     */
    @Tool(name = "quick_visa_check", description = "按护照签发国和目的地国家快速查询签证要求及允许停留天数。"
            + "SUCCESS 只表示查询完成，是否需要签证看 data.visaRequired 和 data.requirement；"
            + "查询失败或未收录不能解释为免签。需要材料、办理流程时再调用 check_visa_requirement。")
    public R<VisaQuickCheckResult> quickVisaCheck(
            @ToolParam(name = "passport", description = "护照签发国 ISO 3166-1 alpha-3 代码，如 CHN；必须基于用户明确的护照信息") String passport,
            @ToolParam(name = "destination", description = "目的地国家 ISO 3166-1 alpha-3 代码，如 JPN；不能填写城市") String destination) {
        String normalizedPassport = normalizeCountry(passport);
        String normalizedDestination = normalizeCountry(destination);
        if (!validCountry(normalizedPassport) || !validCountry(normalizedDestination)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(),
                    "passport 和 destination 必须是三个英文字母的国家代码；护照签发国不明确时请先询问用户");
        }
        try {
            VisaQuickCheckResult result = oriznVisaClient.quickCheck(normalizedPassport, normalizedDestination);
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(),
                    "签证快速查询成功，请核对目的地和护照签发国，并以官方最新入境规定为准。", result);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][quick_visa_check] 业务查询未完成，passport={}, destination={}, code={}",
                    normalizedPassport, normalizedDestination, e.getCode());
            return R.failed(resolveBusinessCode(e), e.getMessage());
        } catch (SystemIntervalException e) {
            log.warn("[TOOL][quick_visa_check] 服务不可用，passport={}, destination={}",
                    normalizedPassport, normalizedDestination);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][quick_visa_check] 查询失败，passport={}, destination={}",
                    normalizedPassport, normalizedDestination, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(),
                    "签证服务暂不可用，不能据此判断免签，请稍后重试或联系管理员。");
        }
    }

    /**
     * 查询签证详情；对套餐未开放的字段不作承诺。
     */
    @Tool(name = "check_visa_requirement", description = "查询护照签发国到目的地国家的签证详情，包括可用的材料、流程及注意事项。"
            + "SUCCESS 表示查询完成；data 中空的材料或流程表示服务未提供，不能推断无需材料或办理。"
            + "extendedDetails 仅包含当前服务套餐实际返回的字段，不得补造费用和办理时长。")
    public R<VisaRequirementResult> checkVisaRequirement(
            @ToolParam(name = "passport", description = "护照签发国 ISO 3166-1 alpha-3 代码，如 CHN；不得从出发城市推断") String passport,
            @ToolParam(name = "destination", description = "目的地国家 ISO 3166-1 alpha-3 代码，如 JPN；不能填写城市") String destination,
            @ToolParam(name = "lang", description = "结果语言代码，如 en、zh；默认 en，非英语可能要求更高服务套餐", required = false) String lang) {
        String normalizedPassport = normalizeCountry(passport);
        String normalizedDestination = normalizeCountry(destination);
        String language = StringUtils.defaultIfBlank(StringUtils.trimToNull(lang), "en").toLowerCase(Locale.ROOT);
        if (!validCountry(normalizedPassport) || !validCountry(normalizedDestination)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(),
                    "passport 和 destination 必须是三个英文字母的国家代码；护照签发国不明确时请先询问用户");
        }
        if (!LANGUAGES.contains(language)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "lang 不是受支持的语言代码");
        }
        try {
            VisaRequirementResult result = oriznVisaClient.checkRequirement(normalizedPassport, normalizedDestination, language);
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(),
                    "签证详情查询成功；仅使用实际返回的字段，并以官方最新入境规定为准。", result);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][check_visa_requirement] 业务查询未完成，passport={}, destination={}, code={}",
                    normalizedPassport, normalizedDestination, e.getCode());
            return R.failed(resolveBusinessCode(e), e.getMessage());
        } catch (SystemIntervalException e) {
            log.warn("[TOOL][check_visa_requirement] 服务不可用，passport={}, destination={}",
                    normalizedPassport, normalizedDestination);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][check_visa_requirement] 查询失败，passport={}, destination={}",
                    normalizedPassport, normalizedDestination, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(),
                    "签证服务暂不可用，不能据此判断免签或所需材料，请稍后重试或联系管理员。");
        }
    }

    private String normalizeCountry(String value) {
        return StringUtils.trimToEmpty(value).toUpperCase(Locale.ROOT);
    }

    private boolean validCountry(String code) {
        return code.length() == 3 && code.chars().allMatch(c -> c >= 'A' && c <= 'Z');
    }

    private String resolveBusinessCode(BusinessRuntimeException exception) {
        if (TripAgentToolResultCode.INVALID_PARAM.getCode().equals(exception.getCode())
                || TripAgentToolResultCode.VISA_RECORD_NOT_FOUND.getCode().equals(exception.getCode())) {
            return exception.getCode();
        }
        return TripAgentToolResultCode.INTERNAL_ERROR.getCode();
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }
}
