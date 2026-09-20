package com.fons.cloud.ai.trip.agent.core;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

/**
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum TripAgent {

    MASTER_AGENT("masterAgent", AgentType.ORCHESTRATION, "主Agent, 负责Agent路由/多意图识别/结果整合等"),

    QUERY_REWRITE_AGENT("queryRewriteAgent", AgentType.ANALYSIS, "问题重写Agent, 负责多轮问题改写与指代消除"),

    ITINERARY_MANAGE_AGENT("itineraryManageAgent", AgentType.BUSINESS_WORKER, "行程单全生命周期管理：收集信息、提交审批、查询差旅单/审批状态、取消申请、修改申请。参数：message（任务描述），可选 session_id（继续会话）。"),

    ITINERARY_PLAN_AGENT("ItineraryPlanAgent", AgentType.BUSINESS_WORKER, "智能行程规划专家, 负责方案生成、比价分析、审核修复闭环"),

    ;

    /**
     * 智能体名称
     */
    private final String agentName;

    /**
     * agent类型
     */
    private final AgentType agentType;

    /**
     * 描述
     */
    private final String description;

    public static TripAgent getAgent(String agentName) {
        if (StringUtils.isBlank(agentName)) {
            return null;
        }
        for (TripAgent agent : values()) {
            if (agent.getAgentName().equals(agentName.trim())) {
                return agent;
            }
        }
        return null;
    }


    enum AgentType {

        /**
         * 分析型Agent
         */
        ANALYSIS,

        /**
         * 任务编排型Agent， 负责任务编排Agent
         */
        ORCHESTRATION,

        /**
         * 业务工作型Agent， 负责执行业务的Agent
         */
        BUSINESS_WORKER,

    }


}
