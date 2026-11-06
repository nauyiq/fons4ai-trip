package com.fons.cloud.ai.trip.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fons.cloud.db.mybatisplus.BaseEntity;
import lombok.Getter;
import lombok.Setter;

/** 差旅单最新已发布的行程规划与审核快照。 */
@Getter
@Setter
@TableName("travel_order_itinerary_plan")
public class TravelOrderItineraryPlan extends BaseEntity {

    /** 差旅单ID；一张差旅单只保留一个已发布方案。 */
    @TableId(value = "order_id", type = IdType.INPUT)
    private String orderId;

    /** 规划结果的完整JSON快照。 */
    private String planSnapshot;

    /** 方案对应的审核JSON快照。 */
    private String reviewSnapshot;

    /** 已发布页面的OSS对象标识，仅供服务端使用。 */
    private String planHtmlUrl;
}
