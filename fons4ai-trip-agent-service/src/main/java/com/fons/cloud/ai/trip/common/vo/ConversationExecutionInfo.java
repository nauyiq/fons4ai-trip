package com.fons.cloud.ai.trip.common.vo;

/**
 * 会话执行占用快照，不表示业务成功、审批结果或工具操作已撤销。
 *
 * @param conversationId 当前用户所属的会话
 * @param pipelineRunId 当前占用该会话的 Pipeline，没有占用时为空
 * @param state IDLE、RUNNING、STOPPING；遗留占用无法确认时为 UNKNOWN
 */
public record ConversationExecutionInfo(String conversationId, String pipelineRunId, String state) {
}
