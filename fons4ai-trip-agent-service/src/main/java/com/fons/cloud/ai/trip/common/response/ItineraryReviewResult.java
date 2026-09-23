package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItineraryRemediationActionType;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewEvidenceSource;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewIssueCode;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewerType;
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
     * 本次审核结果唯一标识，用于精确读取完整报告。
     */
    private String reviewId;

    /**
     * 被审核的规划结果标识，必须来自已保存的 ItineraryPlanningResult。
     */
    private String planId;

    /**
     * 实际使用的审核规则集版本，用于解释规则调整前后的结果差异。
     */
    private String ruleSetVersion;

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
    private List<ProposalReview> proposalReviews;

    /**
     * 各业务审核维度的执行情况和结论，包含无法评估或执行失败的维度。
     */
    private List<DimensionReview> dimensionReviews;

    /**
     * 去重后的完整问题清单，通过 issueId 被方案结论、维度结论和整改项引用。
     */
    private List<ReviewIssue> issues;

    /**
     * 按优先级排序的结构化整改项。没有整改要求时使用空列表。
     */
    private List<RemediationItem> remediationItems;

    /**
     * 根据审核执行状态、可用方案和整改项由 Java 仲裁器计算的下一步动作。
     */
    private ItineraryReviewNextAction nextAction;

    /**
     * 单个代表方案的审核结果。
     *
     * @param proposalId 真实方案标识，不重新生成 P1、P2 等临时编号
     * @param verdict 该方案的最严业务结论
     * @param eligibleForRecommendation 是否允许进入推荐候选；审核不完整时可以保守地设为 false
     * @param issueIds 影响该方案的问题标识
     */
    public record ProposalReview(String proposalId,
                                 ItineraryReviewVerdict verdict,
                                 boolean eligibleForRecommendation,
                                 List<String> issueIds) {

        public ProposalReview {
            issueIds = issueIds == null ? List.of() : List.copyOf(issueIds);
        }
    }

    /**
     * 一个业务审核维度的结果。
     *
     * @param dimension 审核维度
     * @param status 该维度是否完整执行
     * @param reviewerType 执行该维度审核的方式
     * @param reviewerVersion Java规则版本或LLM评估器版本
     * @param verdict 已完成或部分完成评估时的业务结论；NOT_APPLICABLE、NOT_EVALUATED 或 FAILED 时为 null
     * @param issueIds 该维度产生的问题标识
     * @param summary 维度摘要；缺少证据或执行失败时说明原因
     */
    public record DimensionReview(ItineraryReviewDimension dimension,
                                  ItineraryReviewDimensionStatus status,
                                  ItineraryReviewerType reviewerType,
                                  String reviewerVersion,
                                  ItineraryReviewVerdict verdict,
                                  List<String> issueIds,
                                  String summary) {

        public DimensionReview {
            issueIds = issueIds == null ? List.of() : List.copyOf(issueIds);
        }
    }

    /**
     * 可定位、可追溯的审核问题。
     *
     * @param issueId 本次审核内唯一的问题标识
     * @param code 稳定问题编码枚举，供整改说明和前端处理
     * @param dimension 问题所属审核维度
     * @param severity 严重程度
     * @param hardConstraint 是否属于不可被主观评分覆盖的硬约束
     * @param repairableByReplanning 是否可以通过补搜、排除候选或重新规划解决
     * @param affectedProposalIds 受影响的真实方案标识；空列表表示影响整次规划
     * @param evidence 审核证据；缺少证据时不应生成确定性问题
     * @param message 面向业务人员的准确问题描述
     */
    public record ReviewIssue(String issueId,
                              ItineraryReviewIssueCode code,
                              ItineraryReviewDimension dimension,
                              ItineraryReviewSeverity severity,
                              boolean hardConstraint,
                              boolean repairableByReplanning,
                              List<String> affectedProposalIds,
                              List<ReviewEvidence> evidence,
                              String message) {

        public ReviewIssue {
            affectedProposalIds = affectedProposalIds == null
                    ? List.of() : List.copyOf(affectedProposalIds);
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /**
     * 单条审核证据。actualValue 和 expectedValue 仅承接展示值，业务判断仍由审核规则完成。
     *
     * @param source 证据来源
     * @param referenceId 来源对象标识，如 planId、orderId、政策规则ID或候选ID
     * @param field 被审核字段或事实名称
     * @param observedAt 证据采集或生成时间；无独立时间时为 null
     * @param actualValue 实际值
     * @param expectedValue 规则要求或比较值；没有比较值时为 null
     */
    public record ReviewEvidence(ItineraryReviewEvidenceSource source,
                                 String referenceId,
                                 String field,
                                 OffsetDateTime observedAt,
                                 String actualValue,
                                 String expectedValue) {
    }

    /**
     * 由问题清单归并得到的结构化整改动作。
     *
     * @param remediationId 本次审核内唯一的整改标识
     * @param priority 优先级，从 1 开始，数值越小越优先
     * @param issueIds 本整改项处理的问题标识
     * @param actionType 标准动作类型
     * @param affectedProposalIds 需要整改的方案标识
     * @param candidateIds 需要排除、替换或重新比较的真实候选标识；不涉及具体候选时为空列表
     * @param instruction 具体整改说明，不能作为代码分支条件
     */
    public record RemediationItem(String remediationId,
                                  int priority,
                                  List<String> issueIds,
                                  ItineraryRemediationActionType actionType,
                                  List<String> affectedProposalIds,
                                  List<String> candidateIds,
                                  String instruction) {

        public RemediationItem {
            issueIds = issueIds == null ? List.of() : List.copyOf(issueIds);
            affectedProposalIds = affectedProposalIds == null
                    ? List.of() : List.copyOf(affectedProposalIds);
            candidateIds = candidateIds == null ? List.of() : List.copyOf(candidateIds);
        }
    }
}
