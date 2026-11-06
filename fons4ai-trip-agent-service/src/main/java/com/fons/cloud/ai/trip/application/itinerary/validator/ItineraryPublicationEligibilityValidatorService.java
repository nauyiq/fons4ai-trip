package com.fons.cloud.ai.trip.application.itinerary.validator;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.application.itinerary.ItineraryPlanApplicationService;
import com.fons.cloud.ai.trip.common.constants.ItineraryRepairStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewExecutionStatus;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewNextAction;
import com.fons.cloud.ai.trip.common.dto.ItineraryPublicationSource;
import com.fons.cloud.ai.trip.common.dto.ItineraryRepairCycle;
import com.fons.cloud.ai.trip.common.dto.ItineraryScope;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryProposalReview;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
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

    private final TravelOrderDomainService travelOrderDomainService;
    private final ItineraryPlanRepository planRepository;
    private final ItineraryReviewRepository reviewRepository;
    private final ItineraryRepairCycleRepository repairCycleRepository;

    /**
     * 读取可发布的当前方案及审核报告。警告但可推荐的方案允许展示，警告由发布内容呈现。
     *
     * @throws BusinessRuntimeException 当前方案或审核状态不允许发布时抛出
     * @throws SystemIntervalException 已保存的数据彼此矛盾时抛出
     */
    public ItineraryPublicationSource requirePublishable(String userId, String orderId, String conversationId, String planId) {
        // 1. 只按可信用户、会话和真实planId读取已保存的数据
        Assert.isTrue(StringUtils.isNoneBlank(userId, orderId, conversationId),
                () -> invalidRequest("用户、差旅单和会话标识不能为空"));
        Assert.notBlank(planId, () -> invalidRequest("planId不能为空"));
        // 页面发布使用模型给出的单号之前，必须验证当前用户的差旅单归属。
        TravelOrder order = travelOrderDomainService.findByOrderIdAndUserId(orderId.trim(), userId.trim());
        Assert.notNull(order, () -> invalidRequest("未找到当前用户的差旅单"));
        String normalizedPlanId = planId.trim();
        ItineraryPlanningResult plan = planRepository.findById(userId, orderId, conversationId, normalizedPlanId);
        Assert.notNull(plan, () -> invalidState("当前会话未找到对应的行程规划结果"));
        Assert.isTrue(plan.getSourceTravelOrder() != null
                        && orderId.trim().equals(plan.getSourceTravelOrder().orderId()),
                () -> invalidState("方案与差旅单不匹配"));
        ItineraryPlanApplicationService.requireCurrentOrderMatchesPlan(order, plan);

        ItineraryRepairCycle cycle = repairCycleRepository.find(userId, orderId, conversationId);
        ItineraryReviewResult review = reviewRepository.findLatest(userId, orderId, conversationId, normalizedPlanId);
        return requireEligible(plan, cycle, review, normalizedPlanId);
    }

    /** 仅校验旧会话页面的原有规划和审核，调用方必须先确认会话归属。 */
    public ItineraryPublicationSource requireLegacyPublishable(String userId, String conversationId, String planId) {
        Assert.isTrue(StringUtils.isNoneBlank(userId, conversationId, planId),
                () -> invalidRequest("用户、会话和方案标识不能为空"));
        String normalizedPlanId = planId.trim();
        ItineraryPlanningResult plan = planRepository.findLegacyById(userId, conversationId, normalizedPlanId);
        Assert.notNull(plan, () -> invalidState("当前会话未找到历史行程规划结果"));
        // 独立历史方案保持只读；关联差旅单的旧方案仍要校验当前用户和行程条件。
        if (plan.getSourceTravelOrder() != null) {
            String orderId = StringUtils.trimToNull(plan.getSourceTravelOrder().orderId());
            Assert.notNull(orderId, () -> invalidState("历史方案缺少差旅单号，请重新规划"));
            TravelOrder order = travelOrderDomainService.findByOrderIdAndUserId(orderId, userId.trim());
            ItineraryPlanApplicationService.requireCurrentOrderMatchesPlan(order, plan);
        }
        ItineraryRepairCycle cycle = repairCycleRepository.findLegacy(userId, conversationId);
        ItineraryReviewResult review = reviewRepository.findLegacyLatest(userId, conversationId, normalizedPlanId);
        return requireEligible(plan, cycle, review, normalizedPlanId);
    }

    private ItineraryPublicationSource requireEligible(ItineraryPlanningResult plan, ItineraryRepairCycle cycle,
                                                        ItineraryReviewResult review, String normalizedPlanId) {
        // 2. 必须是当前已结束的规划流程；CLOSED本身不代表审核通过
        Assert.isTrue(cycle != null && normalizedPlanId.equals(cycle.planId())
                        && cycle.status() == ItineraryRepairStatus.CLOSED,
                () -> invalidState("当前方案尚未完成审核或已被新方案替代"));
        Assert.isTrue(plan.getUserRequest() != null
                        && cycle.scope().equals(ItineraryScope.from(plan)),
                () -> SystemIntervalException.of("当前规划与修复状态的行程范围不一致"));

        // 3. 只接受当前方案的完整、最终且明确允许推进的审核报告
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
