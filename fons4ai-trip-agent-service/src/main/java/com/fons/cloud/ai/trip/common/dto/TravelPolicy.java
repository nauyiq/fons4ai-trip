package com.fons.cloud.ai.trip.common.dto;

import lombok.*;

import java.io.Serial;
import java.io.Serializable;

/**
 * 指定用户职级、目的城市对应的差旅政策标准。
 * 返回标准不代表差旅审批通过。
 *
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class TravelPolicy implements Serializable {
    /**
     * Java 序列化标识。
     */
    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 生成本政策快照的规则标识，用于规划审核追溯。
     */
    private String policyRuleId;

    /**
     * 用户档案中的职级，例如 P5、P6、P7、P8，用于匹配政策规则的职级区间。
     */
    private String userLevel;

    /**
     * 本政策对应的目的城市，查询时去除首尾空格及末尾的“市”字，避免多城市政策混用。
     */
    private String destinationCity;

    /**
     * 目的城市等级：一线、新一线、二线、其他；二线及其他城市均匹配规则表的“其他”档。
     */
    private String cityTier;
    /**
     * 允许的最高机票舱位，例如经济舱、商务舱；支持斜杠或逗号分隔，已知舱位按等级比较。
     * 实际舱位低于或等于任一允许舱位时符合该项政策。
     */
    private String flightClass;
    /**
     * 酒店每晚房费上限，单位元；按单晚金额比较，不使用多晚订单总额。
     */
    private double hotelLimit;

    /**
     * 酒店星级上限，例如 4 表示最高四星级；规划方案审核会检查此字段。
     * 单笔订单合规校验暂不检查此字段。
     */
    private int hotelStarLimit;

    /**
     * 每日餐饮补贴上限，单位元；当前订单合规校验不检查此字段。
     */
    private double dailyMealLimit;

    /**
     * 每日交通补贴上限，单位元；当前订单合规校验不检查此字段。
     */
    private double dailyTransportLimit;

    /**
     * 允许的最高火车席别，例如二等座、一等座、商务座；支持斜杠或逗号分隔。
     * 实际席别低于或等于任一允许席别时符合该项政策。
     */
    private String trainSeatClass;

    /**
     * 超过该金额需审批，单位元；规划方案审核会生成待审批提醒。
     * 单笔订单合规校验暂不检查此字段。
     */
    private double approvalThreshold;

    /**
     * 最少提前预订天数；规划方案审核按审核当日检查此字段。
     * 单笔订单合规校验暂不检查此字段。
     */
    private int advanceBookingDays;


}
