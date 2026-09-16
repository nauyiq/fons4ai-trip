package com.fons.cloud.ai.trip.agent.model;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum BusinessAgent {

    MASTER_AGENT("masterAgent", "主Agent, 负责Agent路由/多意图识别/结果整合等"),

    QUERY_REWRITE_AGENT("queryRewriteAgent", "问题重写Agent, 负责多轮问题改写与指代消除"),

    ITINERARY_MANAGE_AGENT("itineraryManageAgent", "行程单全生命周期管理：收集信息、提交审批、查询差旅单/审批状态、取消申请、修改申请。参数：message（任务描述），可选 session_id（继续会话）。"),

    ;

    /**
     * 智能体名称
     */
    private final String agentName;

    /**
     * 描述
     */
    private final String description;
}
