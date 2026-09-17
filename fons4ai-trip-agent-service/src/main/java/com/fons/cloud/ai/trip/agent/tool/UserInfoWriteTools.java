package com.fons.cloud.ai.trip.agent.tool;

import cn.hutool.core.lang.Validator;
import com.fons.cloud.ai.trip.common.constants.Gender;
import com.fons.cloud.ai.trip.common.constants.IdType;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.response.UpdateUserBaseLocationResult;
import com.fons.cloud.ai.trip.common.response.UpdateUserContactInfoResult;
import com.fons.cloud.ai.trip.domain.entity.UserProfile;
import com.fons.cloud.ai.trip.domain.service.UserProfileDomainService;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 用户信息写工具集：更新联系/乘机人信息与常驻城市。
 * 读操作见 {@link UserInfoReadTools}，与差旅单/预订工具的 Read/Write 分离惯例保持一致。
 *
 * <p>更新入口对姓名拼音、邮箱、证件类型、证件号、手机号及性别做校验；
 * 用户档案不存在时自动创建，避免"查询提示追问、更新却无法写入"的死锁。
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserInfoWriteTools {
    public static final List<String> TOOLS = List.of("update_user_contact_info", "update_user_base_location");

    private final UserProfileDomainService userProfileDomainService;

    @Tool(name = "update_user_contact_info", description = "用户明确提供或修正联系人、乘机人信息时，保存到当前用户档案。"
            + "至少提供一个字段，省略或空白字段保留原值，不支持清空；档案不存在时自动创建。"
            + "成功后 data.updatedFields 表示本次写入字段，其他回显值为空不代表清空；不代表信息已齐备或预订成功。")
    public R<UpdateUserContactInfoResult> updateUserContactInfo(RuntimeContext context,
            @ToolParam(name = "name_pinyin", description = "姓名拼音，仅使用英文字母，姓在前、名在后，用空格分隔，如 ZHANG SAN；保存时转为大写。", required = false) String namePinyin,
            @ToolParam(name = "email", description = "用户明确提供且格式有效的邮箱地址，如 user@example.com。", required = false) String email,
            @ToolParam(name = "chinese_name", description = "乘机人中文姓名，如张三。", required = false) String chineseName,
            @ToolParam(name = "id_type", description = "证件类型数字：0身份证/1护照/2其他/3回乡证/4军官证/5警官证/6港澳通行证/7台胞证/8台湾通行证/9外国人永久居留身份证。", required = false) Integer idType,
            @ToolParam(name = "id_number", description = "证件号码，按本次或档案已有类型校验；身份证为18位并校验校验位，护照为5至20位字母数字，其他类型为4至30位无空白字符。", required = false) String idNumber,
            @ToolParam(name = "phone", description = "格式有效的11位中国大陆手机号。", required = false) String phone,
            @ToolParam(name = "gender", description = "性别，支持 M/F、男/女、MALE/FEMALE，不区分英文大小写。", required = false) String gender) {
        String userId = context.getUserId();
        log.info("[TOOL][update_user_contact_info] userId={}", userId);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }

        // 空白字段按不更新处理，仅归一化本次传入值
        String normalizedNamePinyin = StringUtils.trimToNull(namePinyin);
        String normalizedEmail = StringUtils.trimToNull(email);
        String normalizedChineseName = StringUtils.trimToNull(chineseName);
        String normalizedIdNumber = StringUtils.trimToNull(idNumber);
        String normalizedPhone = StringUtils.trimToNull(phone);
        String genderInput = StringUtils.trimToNull(gender);
        if (normalizedNamePinyin == null && normalizedEmail == null && normalizedChineseName == null
                && idType == null && normalizedIdNumber == null && normalizedPhone == null && genderInput == null) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "至少需要提供一个联系人或乘机人信息字段");
        }

        IdType inputIdType = IdType.of(idType);
        Gender normalizedGender = normalizeGender(genderInput);
        List<String> errors = new ArrayList<>();
        if (normalizedNamePinyin != null) {
            String[] nameParts = StringUtils.split(normalizedNamePinyin);
            if (nameParts.length < 2 || Arrays.stream(nameParts).anyMatch(part -> !Validator.isWord(part))) {
                errors.add("name_pinyin 必须是英文字母组成的姓名，姓在前、名在后，用空格分隔，如 ZHANG SAN");
            } else {
                normalizedNamePinyin = StringUtils.join(nameParts, ' ');
            }
        }
        if (normalizedEmail != null && !Validator.isEmail(normalizedEmail)) {
            errors.add("email 必须是格式有效的邮箱地址，如 user@example.com");
        }
        if (idType != null && inputIdType == null) {
            errors.add("id_type 必须是 0 至 9 的证件类型编码");
        }
        if (normalizedPhone != null && !Validator.isMobile(normalizedPhone)) {
            errors.add("phone 必须是有效格式的 11 位中国大陆手机号");
        }
        if (genderInput != null && normalizedGender == null) {
            errors.add("gender 无法识别，请使用 M/男/MALE 或 F/女/FEMALE");
        }
        if (!errors.isEmpty()) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "字段格式校验未通过：" + String.join("；", errors));
        }

        try {
            UserProfile existing = userProfileDomainService.getById(userId);
            boolean created = existing == null;
            IdType effectiveIdType = inputIdType != null ? inputIdType : created ? null : existing.getIdType();
            // 更改证件类型时也检查保留的号码，避免保存类型与号码不匹配的组合
            String effectiveIdNumber = normalizedIdNumber != null ? normalizedIdNumber
                    : created ? null : StringUtils.trimToNull(existing.getIdNumber());
            if (effectiveIdNumber != null && (normalizedIdNumber != null || inputIdType != null)) {
                String idError = validateIdNumber(effectiveIdType, effectiveIdNumber);
                if (idError != null) {
                    return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), idError);
                }
            }

            // 仅写入本次明确提供的字段，避免整张旧档案覆盖其他字段
            UserProfile patch = new UserProfile();
            patch.setUserId(userId);
            List<String> updatedFields = new ArrayList<>();
            if (normalizedNamePinyin != null) {
                patch.setNamePinyin(normalizedNamePinyin.toUpperCase(Locale.ROOT));
                updatedFields.add("name_pinyin");
            }
            if (normalizedEmail != null) {
                patch.setEmail(normalizedEmail);
                updatedFields.add("email");
            }
            if (normalizedChineseName != null) {
                patch.setChineseName(normalizedChineseName);
                updatedFields.add("chinese_name");
            }
            if (inputIdType != null) {
                patch.setIdType(inputIdType);
                updatedFields.add("id_type");
            }
            if (normalizedIdNumber != null) {
                patch.setIdNumber(effectiveIdType == IdType.ID_CARD ? normalizedIdNumber.toUpperCase(Locale.ROOT) : normalizedIdNumber);
                updatedFields.add("id_number");
            }
            if (normalizedPhone != null) {
                patch.setPhone(normalizedPhone);
                updatedFields.add("phone");
            }
            if (normalizedGender != null) {
                patch.setGender(normalizedGender);
                updatedFields.add("gender");
            }

            boolean saved = created ? userProfileDomainService.save(patch) : userProfileDomainService.updateById(patch);
            if (!saved) {
                return R.failed(TripAgentToolResultCode.UPDATED_FAILED.getCode(), "用户档案信息保存失败，请查询确认后再处理。");
            }
            UpdateUserContactInfoResult result = new UpdateUserContactInfoResult(userId, created, List.copyOf(updatedFields),
                    patch.getNamePinyin(), patch.getEmail(), patch.getChineseName(),
                    inputIdType == null ? null : inputIdType.getCode(), inputIdType == null ? null : inputIdType.getLabel(),
                    patch.getIdNumber(), patch.getPhone(), normalizedGender == null ? null : normalizedGender.getCode());
            log.info("[TOOL][update_user_contact_info] userId={}, created={}, updatedFields={}", userId, created, updatedFields);
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), created ? "用户档案已创建，已保存本次提供的信息。" : "已更新本次提供的用户档案信息。", result);
        } catch (Exception e) {
            log.error("[TOOL][update_user_contact_info] 用户信息保存异常，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "用户信息保存出现异常，请先查询确认档案状态，不要假定未写入或直接重复执行。");
        }
    }

    @Tool(name = "update_user_base_location", description = "用户明确要求设置或变更常驻、办公城市时，保存到当前用户档案；档案不存在时自动创建。"
            + "仅指定本次出发城市不代表变更常驻城市。成功表示档案已保存，不代表差旅申请已修改。")
    public R<UpdateUserBaseLocationResult> updateUserBaseLocation(RuntimeContext context,
            @ToolParam(name = "base_city", description = "用户明确指定的常驻城市，如北京、深圳，不能为空，首尾去空格后最多30个字符。") String baseCity) {
        String userId = context.getUserId();
        log.info("[TOOL][update_user_base_location] userId={}", userId);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        String city = StringUtils.trimToNull(baseCity);
        if (city == null || city.length() > 30) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "base_city 不能为空且最多30个字符，请确认常驻城市名称");
        }

        try {
            boolean created = userProfileDomainService.getById(userId) == null;
            UserProfile patch = new UserProfile();
            patch.setUserId(userId);
            patch.setBaseCity(city);
            boolean saved = created ? userProfileDomainService.save(patch) : userProfileDomainService.updateById(patch);
            if (!saved) {
                return R.failed(TripAgentToolResultCode.UPDATED_FAILED.getCode(), "用户常驻城市保存失败，请查询确认后再处理。");
            }
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), created ? "用户档案已创建并保存常驻城市。" : "用户常驻城市已更新。",
                    new UpdateUserBaseLocationResult(userId, city, created));
        } catch (Exception e) {
            log.error("[TOOL][update_user_base_location] 常驻城市保存异常，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "常驻城市保存出现异常，请先查询确认档案状态，不要假定未写入或直接重复执行。");
        }
    }

    /**
     * 使用 Hutool 校验证件号码，身份证包含校验位检查，不验证证件真实性或平台实名结果。
     */
    private String validateIdNumber(IdType idType, String idNumber) {
        if (idType == IdType.ID_CARD) {
            return idNumber.length() == 18 && Validator.isCitizenId(idNumber) ? null : "id_number 必须是通过校验位检查的18位身份证号码，末位可为 X";
        }
        if (idType == IdType.PASSPORT) {
            return Validator.isGeneral(idNumber, 5, 20) && !idNumber.contains("_") ? null
                    : "id_number 必须是5至20位字母或数字的护照号码格式";
        }
        return Validator.isBetween(idNumber.length(), 4, 30) && !StringUtils.containsWhitespace(idNumber) ? null
                : "id_number 必须是4至30位无空白字符的证件号码";
    }

    private Gender normalizeGender(String input) {
        if (input == null) {
            return null;
        }
        return switch (input.toUpperCase(Locale.ROOT)) {
            case "M", "MALE", "男" -> Gender.MALE;
            case "F", "FEMALE", "女" -> Gender.FEMALE;
            default -> null;
        };
    }
}
