package com.fons.cloud.ai.trip.common.response;

import java.util.List;

/**
 * 当前用户的联系人、乘机人档案查询结果。
 * 完整性仅表示字段齐备，不代表已通过预订平台的格式或实名校验。
 * @param lastName 拼音姓名中第一个空白分隔部分，按档案中姓在前的约定解析
 * @param firstName 剩余拼音姓名，档案中未分隔时为空字符串
 * @param gender 性别编码，M 为男，F 为女
 * @param hotelComplete 姓名拼音和邮箱是否齐备
 * @param flightComplete 中文姓名、证件类型、证件号码、手机号和性别是否齐备
 * @param complete 酒店及机票两类信息是否均齐备，不作为单一业务的必需条件
 * @param missingFields 缺失或需要修正的业务字段，仅按当前任务所需部分追问
 * @author hongqy
 */
public record QueryUserContactInfoResult(
        String userId,
        String namePinyin,
        String lastName,
        String firstName,
        String email,
        String chineseName,
        Integer idType,
        String idTypeLabel,
        String idNumber,
        String phone,
        String gender,
        boolean hotelComplete,
        boolean flightComplete,
        boolean complete,
        List<String> missingFields) {
}
