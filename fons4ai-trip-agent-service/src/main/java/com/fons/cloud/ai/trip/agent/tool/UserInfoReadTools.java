package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.common.constants.IdType;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.response.QueryUserBaseLocationResult;
import com.fons.cloud.ai.trip.common.response.QueryUserContactInfoResult;
import com.fons.cloud.ai.trip.domain.entity.UserProfile;
import com.fons.cloud.ai.trip.domain.service.UserProfileDomainService;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前用户联系信息、乘机人信息及常驻城市的只读查询工具。
 * 每次按运行时可信 userId 查询档案，不使用业务上下文缓存。
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInfoReadTools {
    public static final List<String> TOOLS = List.of("query_user_contact_info", "query_user_base_location");

    private final UserProfileDomainService userProfileDomainService;

    @Tool(name = "query_user_contact_info", description = "查询当前用户的联系人和乘机人档案，用于酒店、机票预订。"
            + "查询成功不代表信息齐备：酒店读取 data.hotelComplete，机票读取 data.flightComplete；按 data.missingFields 仅追问本次业务所需信息。"
            + "字段齐备不代表通过平台校验；档案不存在仍返回成功和缺失字段。")
    public R<QueryUserContactInfoResult> queryUserContactInfo(RuntimeContext context) {
        String userId = context.getUserId();
        log.info("[TOOL][query_user_contact_info] userId={}", userId);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }

        try {
            UserProfile profile = userProfileDomainService.getById(userId);
            QueryUserContactInfoResult result = buildContactInfo(userId, profile);
            String message;
            if (profile == null) {
                message = "未找到用户档案，请根据本次酒店或机票预订所需字段向用户补充信息。";
            } else if (!result.complete()) {
                message = "用户档案信息尚未全部齐备，请结合 hotelComplete、flightComplete 和 missingFields，仅补充本次业务所需信息。";
            } else {
                message = "用户联系人及乘机人档案字段已齐备，实际预订仍需遵守平台校验。";
            }
            log.info("[TOOL][query_user_contact_info] userId={}, hotelComplete={}, flightComplete={}",
                    userId, result.hotelComplete(), result.flightComplete());
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, result);
        } catch (Exception e) {
            log.error("[TOOL][query_user_contact_info] 用户档案查询失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "用户档案查询失败，请稍后重试，不能据此判断档案或字段不存在。");
        }
    }

    @Tool(name = "query_user_base_location", description = "查询当前用户的常驻城市，仅在用户未指定出发城市时作为默认值。"
            + "data.baseCity 为空表示没有可用默认值，需要询问出发城市；不覆盖用户明确指定的地点。档案不存在仍返回成功和空城市。")
    public R<QueryUserBaseLocationResult> queryUserBaseLocation(RuntimeContext context) {
        String userId = context.getUserId();
        log.info("[TOOL][query_user_base_location] userId={}", userId);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }

        try {
            UserProfile profile = userProfileDomainService.getById(userId);
            String baseCity = profile == null ? "" : StringUtils.trimToEmpty(profile.getBaseCity());
            String message;
            if (profile == null) {
                message = "未找到用户档案，请向用户询问本次出发城市。";
            } else if (baseCity.isEmpty()) {
                message = "用户未配置常驻城市，请向用户询问本次出发城市。";
            } else {
                message = "已查询到用户常驻城市，仅在用户未指定出发城市时使用。";
            }
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, new QueryUserBaseLocationResult(userId, baseCity));
        } catch (Exception e) {
            log.error("[TOOL][query_user_base_location] 常驻城市查询失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "用户常驻城市查询失败，不能据此判断用户未配置常驻城市。");
        }
    }

    private QueryUserContactInfoResult buildContactInfo(String userId, UserProfile profile) {
        String namePinyin = profile == null ? null : StringUtils.trimToNull(profile.getNamePinyin());
        String email = profile == null ? null : StringUtils.trimToNull(profile.getEmail());
        String chineseName = profile == null ? null : StringUtils.trimToNull(profile.getChineseName());
        Integer idType = profile == null || profile.getIdType() == null ? null : profile.getIdType().getCode();
        String idNumber = profile == null ? null : StringUtils.trimToNull(profile.getIdNumber());
        String phone = profile == null ? null : StringUtils.trimToNull(profile.getPhone());
        String gender = profile == null || profile.getGender() == null ? null : profile.getGender().getCode();

        boolean hasNamePinyin = namePinyin != null;
        boolean hasEmail = email != null;
        boolean hasChineseName = chineseName != null;
        IdType idTypeEnum = IdType.of(idType);
        boolean hasIdType = idTypeEnum != null;
        boolean hasIdNumber = idNumber != null;
        boolean hasPhone = phone != null;
        boolean hasGender = gender != null;

        String lastName = null;
        String firstName = null;
        if (hasNamePinyin) {
            String[] parts = namePinyin.split("\\s+", 2);
            lastName = parts[0];
            firstName = parts.length > 1 ? parts[1] : "";
        }

        List<String> missingFields = new ArrayList<>();
        if (!hasNamePinyin) {
            missingFields.add("namePinyin");
        }
        if (!hasEmail) {
            missingFields.add("email");
        }
        if (!hasChineseName) {
            missingFields.add("chineseName");
        }
        if (!hasIdType) {
            missingFields.add("idType");
        }
        if (!hasIdNumber) {
            missingFields.add("idNumber");
        }
        if (!hasPhone) {
            missingFields.add("phone");
        }
        if (!hasGender) {
            missingFields.add("gender");
        }
        boolean hotelComplete = hasNamePinyin && hasEmail;
        boolean flightComplete = hasChineseName && hasIdType && hasIdNumber && hasPhone && hasGender;
        String idTypeLabel = idType == null ? null : idTypeEnum == null ? "未知" : idTypeEnum.getLabel();
        return new QueryUserContactInfoResult(userId, namePinyin, lastName, firstName, email, chineseName,
                idType, idTypeLabel, idNumber, phone, gender, hotelComplete, flightComplete,
                hotelComplete && flightComplete, List.copyOf(missingFields));
    }
}
