package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.ProposalTag;

import java.math.BigDecimal;
import java.util.List;

/**
 * plan_itinerary 返回给模型的规划摘要，由外层 R 承接工具成功码或失败信息。
 * 完整结果使用 ItineraryPlanningResult 保存，本对象不重复输出交通、酒店、评分明细及风险报告。
 * 规划结果成功保存后才返回本摘要，不表示已审核通过、用户已确认或可以直接预订。
 *
 * @param planId 本次已保存规划结果的标识，供后续方案读取、审核和展示使用，不是存储 Key
 * @param combinationCount 完成有效性过滤后的组合数量，与完整结果中的 combinationCount 一致
 * @param proposalCount 去重后的代表方案数量，成功时为 1 至 4，必须等于 proposals 的条数
 * @param proposals 代表方案摘要，与完整结果保持相同顺序，按综合分降序排列
 * @author hongqy
 */
public record PlanItineraryResult(
        String planId,
        long combinationCount,
        int proposalCount,
        List<ProposalSummary> proposals) {

    /**
     * 一套代表方案的标识、标签及综合分，不承接 LLM 生成的说明文案。
     * 同一方案可命中多个标签，应保留全部标签；综合分最高不等于审核后推荐方案。
     * 需要费用、行程明细或风险信息时，必须读取对应的完整结果，不能凭摘要推测。
     *
     * @param proposalId 与完整结果一致的方案标识，与 planId 一起定位方案
     * @param tags 与完整结果一致的代表方案标签，不是审核状态或用户选择
     * @param overallScore 综合分，0 至 100，与完整方案 scores.overall 一致，不重新计算或让模型填写
     */
    public record ProposalSummary(String proposalId, List<ProposalTag> tags, BigDecimal overallScore) {
    }
}
