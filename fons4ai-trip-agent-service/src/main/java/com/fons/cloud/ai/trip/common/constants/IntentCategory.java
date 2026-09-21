package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 差旅对话意图分类枚举。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum IntentCategory {

    /**
     * 用户要提交新的差旅申请 / 出差审批。
     */
    TRAVEL_APPLICATION("travel_application", "ItineraryManageAgent", "用户要提交新的差旅申请/出差审批"),
    /**
     * 用户要取消出差申请或审批单。
     */
    TRAVEL_CANCEL("travel_cancel", "ItineraryManageAgent", "用户要取消出差申请或审批单"),
    /**
     * 用户要修改差旅申请信息。
     */
    TRAVEL_MODIFY("travel_modify", "ItineraryManageAgent", "用户要修改差旅申请信息"),
    /**
     * 用户查询审批进度、审批状态、审批结果。
     */
    APPROVAL_QUERY("approval_query", "ItineraryManageAgent", "用户查询审批进度/状态/结果"),
    /**
     * 用户查询已有差旅单详情/状态。
     */
    TRAVEL_ORDER_QUERY("travel_order_query", "ItineraryManageAgent", "用户查询已有差旅单详情/状态"),
    /**
     * 用户要求规划行程、做方案。
     */
    ITINERARY_PLANNING("itinerary_planning", "ItineraryPlanAgent", "用户要求规划行程、做方案"),
    /**
     * 用户要查航班。
     */
    FLIGHT_SEARCH("flight_search", "ItineraryPlanAgent", "用户要查航班"),
    /**
     * 用户要查火车。
     */
    TRAIN_SEARCH("train_search", "ItineraryPlanAgent", "用户要查火车"),
    /**
     * 用户要查酒店。
     */
    HOTEL_SEARCH("hotel_search", "ItineraryPlanAgent", "用户要查酒店"),
    /**
     * 用户要预订/改签/取消已选方案。
     */
    BOOKING("booking", "BookingAgent", "用户要预订/改签/取消已选方案"),
    /**
     * 用户要报销、识别发票、生成报销单。
     */
    REIMBURSEMENT("reimbursement", "ReimbursementAgent", "用户要报销、识别发票、生成报销单"),
    /**
     * 用户查询差旅政策、餐标、酒店标准、签证/入境政策。
     */
    POLICY_QUERY("policy_query", "InfoAgent", "用户查询差旅政策/餐标/酒店标准/签证入境政策"),
    /**
     * 用户查询目的地景点、旅游信息。
     */
    ATTRACTIONS_QUERY("attractions_query", "InfoAgent", "用户查询目的地景点、旅游信息"),
    /**
     * 天气、地图、交通、目的地新闻等通用信息查询。
     */
    GENERAL_INFO("general_info", "InfoAgent", "天气/地图/交通/目的地新闻等通用信息查询"),
    /**
     * 用户打招呼、寒暄。
     */
    GREETING("greeting", "MasterAgent", "用户打招呼、寒暄"),
    /**
     * 无法明确分类或信息严重不足。
     */
    UNKNOWN("unknown", "MasterAgent", "无法明确分类或信息严重不足");

    /**
     * 系统 Prompt / 路由 JSON 中使用的意图标识。
     */
    private final String code;

    /**
     * 默认的目标子智能体名称（与 Spring bean 名称保持一致）。
     */
    private final String defaultTargetAgent;

    /**
     * 意图的人类可读中文说明。
     */
    private final String description;

}
