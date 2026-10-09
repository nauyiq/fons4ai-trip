package com.fons.cloud.ai.trip.application.itinerary.validator;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.constants.ItineraryRepairStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.ItineraryPublicationSource;
import com.fons.cloud.ai.trip.common.dto.ItineraryRepairCycle;
import com.fons.cloud.ai.trip.common.dto.ItineraryScope;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryProposalReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanRepository;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryRepairCycleRepository;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryReviewRepository;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

/**
 * 发布行程推荐方案前的后端资格校验。
 * 仅组合当前规划、最新审核报告和修复状态，不负责渲染或实际发布。
 *
 * @author hongqy
 */
@Service
@RequiredArgsConstructor
public class ItineraryPublicationEligibilityValidatorService {

    private final ItineraryPlanRepository planRepository;
    private final ItineraryReviewRepository reviewRepository;
    private final ItineraryRepairCycleRepository repairCycleRepository;

    /**
     * 读取可发布的当前方案及审核报告。警告但可推荐的方案允许展示，警告由发布内容呈现。
     *
     * @throws BusinessRuntimeException 当前方案或审核状态不允许发布时抛出
     * @throws SystemIntervalException 已保存的数据彼此矛盾时抛出
     */
    public ItineraryPublicationSource requirePublishable(CandidateOwner owner, String planId) {
        // 1. 只按可信用户、会话和真实planId读取已保存的数据
        Assert.notNull(owner, () -> invalidRequest("方案归属不能为空"));
        Assert.notBlank(planId, () -> invalidRequest("planId不能为空"));
        String normalizedPlanId = planId.trim();
        ItineraryPlanningResult plan = planRepository.findById(owner, normalizedPlanId);
        Assert.notNull(plan, () -> invalidState("当前会话未找到对应的行程规划结果"));

        // 2. 必须是当前已结束的规划流程；CLOSED本身不代表审核通过
        ItineraryRepairCycle cycle = repairCycleRepository.find(owner);
        Assert.isTrue(cycle != null && normalizedPlanId.equals(cycle.planId())
                        && cycle.status() == ItineraryRepairStatus.CLOSED,
                () -> invalidState("当前方案尚未完成审核或已被新方案替代"));
        Assert.isTrue(plan.getUserRequest() != null
                        && cycle.scope().equals(ItineraryScope.from(plan)),
                () -> SystemIntervalException.of("当前规划与修复状态的行程范围不一致"));

        // 3. 只接受当前方案的完整、最终且明确允许推进的审核报告
        ItineraryReviewResult review = reviewRepository.findLatest(owner, normalizedPlanId);
        Assert.isTrue(review != null
                        && review.getExecutionStatus() == ItineraryReviewExecutionStatus.COMPLETE
                        && review.getNextAction() == ItineraryReviewNextAction.PROCEED,
                () -> invalidState("当前方案没有允许发布的完整审核结果"));
        String recommendedId = StringUtils.trimToNull(review.getRecommendedProposalId());
        Assert.isTrue(recommendedId != null, () -> invalidState("审核结果没有可推荐方案"));

        // 4. 推荐ID必须同时存在于原规划及对应的可推荐方案审核结果中
        Proposal recommended = plan.getProposals() == null ? null : plan.getProposals().stream()
                .filter(proposal -> proposal != null && recommendedId.equals(proposal.proposalId()))
                .findFirst().orElse(null);
        ItineraryProposalReview proposalReview = review.getProposalReviews() == null ? null
                : review.getProposalReviews().stream()
                .filter(item -> item != null && recommendedId.equals(item.proposalId()))
                .findFirst().orElse(null);
        Assert.isTrue(recommended != null && proposalReview != null
                        && proposalReview.eligibleForRecommendation(),
                () -> SystemIntervalException.of("审核推荐方案与已保存规划或方案审核结论不一致"));
        return new ItineraryPublicationSource(plan, review, recommended);
    }

    private BusinessRuntimeException invalidRequest(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }

    private BusinessRuntimeException invalidState(String message) {
        return BusinessRuntimeException.of(ResultCode.ILLEGAL_REQUEST_LIMITED.getCode(), message);
    }
}
