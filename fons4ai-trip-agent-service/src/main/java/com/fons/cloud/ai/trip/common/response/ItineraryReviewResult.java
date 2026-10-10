package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 一次行程规划审核的完整结构化结果，供保存、读取、整改说明和页面展示使用。
 * 审核结果只针对指定 planId 的不可变规划快照；不表示用户已选择方案、完成审批或完成预订。
 *
 * <p>executionStatus 表示审核过程是否完整执行，verdict 表示业务方案是否存在风险或阻断，
 * 两者不能互相替代。PARTIAL 或 FAILED 时不得仅凭已完成维度声称“审核通过”。
 *
 * @author hongqy
 */
@Getter
@Setter
@Builder
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class ItineraryReviewResult {

    /**
     * 本次审核结果唯一标识，用于标记和关联报告；最新报告按planId读取。
     */
    private String reviewId;

    /**
     * 被审核的规划结果标识，必须来自已保存的 ItineraryPlanningResult。
     */
    private String planId;

    /**
     * 审核完成时间，包含明确 UTC 偏移。
     */
    private OffsetDateTime reviewedAt;

    /**
     * 整次审核的执行完整性。
     */
    private ItineraryReviewExecutionStatus executionStatus;

    /**
     * 整次审核的业务结论。FAILED 时可以为 null；PARTIAL 时只能表示已完成维度的最严结论。
     */
    private ItineraryReviewVerdict verdict;

    /**
     * 审核后推荐的真实 proposalId。没有可推荐方案或审核结果不完整时为 null。
     */
    private String recommendedProposalId;

    /**
     * 面向调用方的审核摘要，不替代结构化问题和整改项。
     */
    private String summary;

    /**
     * 每个代表方案的审核结论；一个方案被阻断不会自动阻断其他可行方案。
     */
    private List<ItineraryProposalReview> proposalReviews;

    /**
     * 各业务审核维度的执行情况和结论，包含无法评估或执行失败的维度。
     */
    private List<ItineraryDimensionReview> dimensionReviews;

    /**
     * 去重后的完整问题清单，通过 issueId 被方案结论、维度结论和整改项引用。
     */
    private List<ItineraryReviewIssue> issues;

    /**
     * 按优先级排序的结构化整改项。没有整改要求时使用空列表。
     */
    private List<ItineraryRemediationItem> remediationItems;

    /**
     * 根据审核执行状态、可用方案和整改项由 Java 仲裁器计算，
     * 再由修复流程按次数上限收口的下一步动作。
     */
    private ItineraryReviewNextAction nextAction;

}
