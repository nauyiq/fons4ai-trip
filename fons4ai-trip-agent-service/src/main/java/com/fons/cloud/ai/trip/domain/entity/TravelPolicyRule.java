package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.*;

/**
 * 差旅政策规则表
 *
 * @author hongqy
 */
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@TableName("travel_policy_rule")
public class TravelPolicyRule extends BaseEntity {

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 职级区间下限（数字）
     */
    private Integer levelMin;

    /**
     * 职级区间上限（99表示无上限）
     */
    private Integer levelMax;

    /**
     * 城市等级：一线/新一线/其他
     */
    private String cityTier;

    /**
     * 允许的最高机票舱位
     */
    private String flightClass;

    /**
     * 允许的最高高铁座位
     */
    private String trainSeatClass;

    /**
     * 酒店每晚上限（元）
     */
    private Double hotelLimit;

    /**
     * 酒店星级上限
     */
    private Integer hotelStarLimit;

    /**
     * 餐补每日上限（元）
     */
    private Double dailyMealLimit;

    /**
     * 交通补贴每日上限（元）
     */
    private Double dailyTransportLimit;

    /**
     * 审批金额阈值（超过此金额需审批）
     */
    private Double approvalThreshold;

    /**
     * 提前预订最少天数
     */
    private Integer advanceBookingDays;

}
