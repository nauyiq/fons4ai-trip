package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.ai.trip.common.constants.Gender;
import com.fons.cloud.ai.trip.common.constants.IdType;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import com.github.houbb.sensitive.annotation.strategy.SensitiveStrategyCardId;
import com.github.houbb.sensitive.annotation.strategy.SensitiveStrategyChineseName;
import com.github.houbb.sensitive.annotation.strategy.SensitiveStrategyEmail;
import com.github.houbb.sensitive.annotation.strategy.SensitiveStrategyPhone;
import lombok.*;

/**
 * @author hongqy
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_profile")
public class UserProfile extends BaseEntity {

    /**
     * 用户id
     */
    @TableId
    private String userId;

    /**
     * 常驻城市
     */
    private String baseCity;

    /**
     * 用户职级，如 P5 / P6 / P7 / P8，对应差旅政策中的前置条件
     */
    private String level;

    /**
     * 姓名拼音（大写，姓在前名在后，中间空格分隔），如 "ZHANG SAN"，用于酒店预订联系人
     */
    private String namePinyin;

    /**
     * 用户邮箱，用于酒店预订下单时的联系人信息
     */
    @SensitiveStrategyEmail
    private String email;

    /**
     * 中文姓名，如 "张三"，用于机票预订乘客信息
     */
    @SensitiveStrategyChineseName
    private String chineseName;

    /**
     * 证件类型（0-身份证 1-护照 2-其他 3-回乡证 4-军官证 5-警官证 6-港澳通行证 7-台胞证 8-台湾通行证 9-外国人永久居留身份证）
     */
    private IdType idType;

    /**
     * 证件号码（身份证 / 护照等），用于机票预订
     */
    @SensitiveStrategyCardId
    private String idNumber;

    /**
     * 手机号，用于机票预订联系人
     */
    @SensitiveStrategyPhone
    private String phone;

    /**
     * 性别：M-男，F-女
     */
    private Gender gender;


}
