package com.fons.cloud.ai.trip.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fons.cloud.ai.trip.domain.entity.TravelOrderItineraryPlan;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 已发布差旅行程方案持久化。 */
@Mapper
public interface TravelOrderItineraryPlanMapper extends BaseMapper<TravelOrderItineraryPlan> {
    /** 批量查询有已发布方案的差旅单ID，不加载规划和审核JSON。 */
    @Select({"<script>",
            "SELECT order_id FROM travel_order_itinerary_plan WHERE order_id IN",
            "<foreach collection='orderIds' item='orderId' open='(' separator=',' close=')'>",
            "#{orderId}",
            "</foreach>",
            "</script>"})
    List<String> findPublishedOrderIds(@Param("orderIds") List<String> orderIds);
}
