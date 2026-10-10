package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimensionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewEvidenceSource;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewIssueCode;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewerType;
import com.fons.cloud.ai.trip.common.constants.ItinerarySubjectiveFindingType;
import com.fons.cloud.ai.trip.common.dto.ItineraryDimensionReviewResult;
import com.fons.cloud.ai.trip.common.dto.ItineraryReviewContext;
import com.fons.cloud.ai.trip.common.dto.ItineraryReviewCoverageGap;
import com.fons.cloud.ai.trip.common.dto.ItinerarySubjectiveAssessment;
import com.fons.cloud.ai.trip.common.dto.ItinerarySubjectiveFinding;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryDimensionReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewEvidence;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewIssue;
import com.fons.cloud.ai.trip.infrastructure.client.api.ItinerarySubjectiveAssessmentGateway;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 将模型的主观发现校验并转换为现有维度审核契约。
 * 模型不能指定硬约束、最终推荐或未提供的事实标识。
 *
 * @author hongqy
 */
public final class SubjectiveDimensionReviewer implements ItineraryDimensionReviewer {

    private static final int MAX_FINDINGS = 8;
    private static final int MAX_MESSAGE_LENGTH = 300;

    private final ItineraryReviewDimension dimension;
    private final ItinerarySubjectiveAssessmentGateway gateway;

    public SubjectiveDimensionReviewer(ItineraryReviewDimension dimension,
                                       ItinerarySubjectiveAssessmentGateway gateway) {
        if (dimension != ItineraryReviewDimension.EXPERIENCE_AND_PREFERENCE
                && dimension != ItineraryReviewDimension.RESILIENCE) {
            throw SystemIntervalException.of("不支持的主观审核维度：" + dimension);
        }
        this.dimension = dimension;
        if (gateway == null) {
            throw SystemIntervalException.of("主观审核模型端口不能为空");
        }
        this.gateway = gateway;
    }

    @Override
    public ItineraryReviewDimension dimension() {
        return dimension;
    }

    @Override
    public ItineraryReviewerType reviewerType() {
        return ItineraryReviewerType.LLM_ASSESSOR;
    }

    @Override
    public ItineraryDimensionReviewResult review(ItineraryPlanningResult planningResult,
                                                 ItineraryReviewContext context) {
        // 1. 调用模型评估当前维度，并校验返回的问题列表是否有效
        ItinerarySubjectiveAssessment assessment = gateway.assess(dimension, planningResult, context);
        if (assessment == null || assessment.findings() == null
                || assessment.findings().size() > MAX_FINDINGS) {
            throw SystemIntervalException.of("主观审核未返回有效的问题列表");
        }
        // 2. 逐条核对方案、事实标识与证据来源，再转换为可信审核问题
        Map<String, ItineraryReviewEvidence> facts = context.facts();
        List<ItineraryReviewIssue> issues = new ArrayList<>();
        for (int index = 0; index < assessment.findings().size(); index++) {
            ItinerarySubjectiveFinding finding = assessment.findings().get(index);
            if (finding == null || StringUtils.isBlank(finding.proposalId())
                    || finding.type() == null
                    || StringUtils.isBlank(finding.message())
                    || finding.message().length() > MAX_MESSAGE_LENGTH
                    || finding.evidenceIds() == null || finding.evidenceIds().isEmpty()
                    || finding.evidenceIds().size() > 5) {
                throw SystemIntervalException.of("主观审核发现缺少有效的方案、说明或证据");
            }
            String proposalId = finding.proposalId().trim();
            String proposalFactId = "proposal:" + proposalId;
            if (!facts.containsKey(proposalFactId)
                    || !finding.evidenceIds().contains(proposalFactId)) {
                throw SystemIntervalException.of("主观审核引用了未知方案或缺少方案证据");
            }
            List<ItineraryReviewEvidence> evidence = finding.evidenceIds().stream()
                    .map(id -> {
                        ItineraryReviewEvidence item = facts.get(id);
                        if (item == null) {
                            throw SystemIntervalException.of("主观审核引用了未知事实");
                        }
                        return item;
                    })
                    .distinct()
                    .toList();
            validateFindingType(finding.type(), evidence);
            ItineraryReviewIssueCode code = dimension == ItineraryReviewDimension.RESILIENCE
                    ? ItineraryReviewIssueCode.RESILIENCE_RISK
                    : ItineraryReviewIssueCode.EXPERIENCE_OR_PREFERENCE_RISK;
            issues.add(new ItineraryReviewIssue(code.name() + ":" + proposalId + ":" + index,
                    code, dimension, ItineraryReviewSeverity.WARNING, false, false,
                    List.of(proposalId), evidence, finding.message().trim()));
        }
        // 3. 汇总有证据支持的问题及信息覆盖范围，形成主观维度结论
        String summary = issues.isEmpty() ? "未发现有证据支持的主观风险" : "发现" + issues.size() + "项主观风险";
        List<String> uncovered = context.coverageGaps().stream()
                .filter(gap -> gap.dimension() == dimension)
                .map(ItineraryReviewCoverageGap::message)
                .toList();
        if (!uncovered.isEmpty()) {
            summary += "；未覆盖：" + String.join("；", uncovered);
        }
        ItineraryDimensionReview dimensionReview = new ItineraryDimensionReview(dimension,
                ItineraryReviewDimensionStatus.COMPLETE, reviewerType(), reviewerVersion(),
                ItineraryReviewSupport.verdictOf(issues),
                issues.stream().map(ItineraryReviewIssue::issueId).toList(), summary);
        return new ItineraryDimensionReviewResult(dimensionReview, issues);
    }

    private void validateFindingType(ItinerarySubjectiveFindingType type,
                                     List<ItineraryReviewEvidence> evidence) {
        boolean valid = switch (type) {
            case EXPERIENCE -> dimension == ItineraryReviewDimension.EXPERIENCE_AND_PREFERENCE;
            case PREFERENCE -> dimension == ItineraryReviewDimension.EXPERIENCE_AND_PREFERENCE
                    && hasSource(evidence, ItineraryReviewEvidenceSource.USER_PREFERENCE);
            case WEATHER -> dimension == ItineraryReviewDimension.RESILIENCE
                    && hasSource(evidence, ItineraryReviewEvidenceSource.WEATHER);
            case NEWS -> dimension == ItineraryReviewDimension.RESILIENCE
                    && hasSource(evidence, ItineraryReviewEvidenceSource.DESTINATION_NEWS);
            case STRUCTURAL_RESILIENCE -> dimension == ItineraryReviewDimension.RESILIENCE;
        };
        if (!valid) {
            throw SystemIntervalException.of("主观审核发现类型与维度或证据来源不匹配");
        }
    }

    private boolean hasSource(List<ItineraryReviewEvidence> evidence, ItineraryReviewEvidenceSource source) {
        return evidence.stream().anyMatch(item -> item.source() == source);
    }
}
