package com.fons.cloud.ai.trip.common.response;

import java.util.List;

/**
 * 用户联系人、乘机人信息写入结果。
 * 仅回显本次写入的字段，其他值为空不表示清空了档案。
 * @param created 是否新建用户档案
 * @param updatedFields 本次写入的工具参数名
 * @param idType 证件类型数字编码，与查询工具保持一致
 * @param gender 性别编码，M 为男，F 为女
 * @author hongqy
 */
public record UpdateUserContactInfoResult(
        String userId,
        boolean created,
        List<String> updatedFields,
        String namePinyin,
        String email,
        String chineseName,
        Integer idType,
        String idTypeLabel,
        String idNumber,
        String phone,
        String gender) {
}
