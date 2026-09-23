package com.fons.cloud.ai.trip.common.constants;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 单条整改事项的标准动作类型，供 Plan Agent 选择后续工具和业务流程。
 *
 * @author hongqy
 */
@Getter
@AllArgsConstructor
public enum ItineraryRemediationActionType {

    RESEARCH_TRANSPORT("重新搜索交通候选"),
    RESEARCH_HOTEL("重新搜索酒店或房型"),
    LOAD_TRAVEL_POLICY("重新加载差旅政策"),
    EXCLUDE_CANDIDATE("排除指定候选"),
    REPLAN("使用现有候选重新规划"),
    REQUEST_USER_INPUT("请求用户补充或选择"),
    REQUIRE_MANUAL_APPROVAL("需要人工审批或豁免"),
    NO_ACTION("无需整改动作");

    private final String label;
}
