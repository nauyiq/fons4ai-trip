package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.constants.ItineraryRepairStatus;

/**
 * 单个用户会话当前行程的最小修复状态，不保存规划或审核历史版本。
 *
 * @param scope 当前行程范围，用于识别日期或路线改变后的新任务
 * @param planId 当前待审核或待修复的真实规划标识
 * @param runId 最近一次生成规划或审核的运行标识
 * @param replanCount 已成功生成的修复方案次数，首次规划计为零
 * @param reviewRetryCount 当前方案因审核执行不完整而重试的次数
 * @param status 当前流程状态
 * @author hongqy
 */
public record ItineraryRepairCycle(ItineraryScope scope, String planId, String runId,
                                   int replanCount, int reviewRetryCount, ItineraryRepairStatus status) {
}
