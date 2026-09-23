package com.fons.cloud.ai.trip.application.itinerary;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.application.itinerary.reviewer.ItineraryReviewContext;
import com.fons.cloud.ai.trip.application.itinerary.reviewer.ItineraryReviewOrchestrator;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TravelOrderReference;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanRepository;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * 行程规划审核应用服务。
 * 按可信用户和会话读取已保存的规划，并在每次审核前重新加载关联差旅单，
 * 再交由固定审核规则集生成结构化审核结果。
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryReviewApplicationService {

    private final ItineraryPlanRepository planRepository;
    private final TravelOrderDomainService travelOrderDomainService;
    private final ItineraryReviewOrchestrator reviewOrchestrator = new ItineraryReviewOrchestrator();

    /**
     * 审核当前用户、当前会话下的指定规划结果。
     *
     * <p>关联差旅单时，只使用本次按用户重新查询的当前状态参与审核；规划内保存的差旅单快照
     * 仅用于确认原始关联关系。差旅单不存在或当前数据不完整时，审核结果会明确标记为未完成。
     *
     * @param owner 可信用户和会话归属
     * @param planId 已保存的规划结果标识
     * @return 结构化审核结果；规划不存在时返回null
     */
    public ItineraryReviewResult review(CandidateOwner owner, String planId) {
        Assert.notNull(owner, () -> parameterError("审核归属不能为空"));
        Assert.notBlank(planId, () -> parameterError("planId不能为空"));
        String normalizedPlanId = planId.trim();

        ItineraryPlanningResult planningResult = planRepository.findById(owner, normalizedPlanId);
        if (planningResult == null) {
            return null;
        }

        TravelOrderReference currentTravelOrder = loadCurrentTravelOrder(owner, planningResult);
        ItineraryReviewContext context = ItineraryReviewContext.now(currentTravelOrder);
        ItineraryReviewResult result = reviewOrchestrator.review(planningResult, context);
        log.info("[ItineraryReviewApplicationService] 行程规划审核完成，userId={}, conversationId={}, "
                        + "planId={}, reviewId={}, executionStatus={}, verdict={}, nextAction={}",
                owner.userId(), owner.conversationId(), normalizedPlanId, result.getReviewId(),
                result.getExecutionStatus(), result.getVerdict(), result.getNextAction());
        return result;
    }

    private TravelOrderReference loadCurrentTravelOrder(CandidateOwner owner,
                                                        ItineraryPlanningResult planningResult) {
        TravelOrderReference sourceTravelOrder = planningResult.getSourceTravelOrder();
        if (sourceTravelOrder == null || StringUtils.isBlank(sourceTravelOrder.orderId())) {
            return null;
        }

        TravelOrder order = travelOrderDomainService.findByOrderIdAndUserId(
                sourceTravelOrder.orderId(), owner.userId());
        if (order == null) {
            log.warn("[ItineraryReviewApplicationService] 规划关联的差旅单不存在，userId={}, planId={}, orderId={}",
                    owner.userId(), planningResult.getPlanId(), sourceTravelOrder.orderId());
            return null;
        }
        return new TravelOrderReference(StringUtils.trimToNull(order.getOrderId()),
                StringUtils.trimToNull(order.getApprovalId()), order.getStatus(),
                StringUtils.trimToNull(order.getDepartureCity()),
                StringUtils.trimToNull(order.getDestination()),
                parseDate(order.getDepartureDate()), parseDate(order.getReturnDate()));
    }

    private LocalDate parseDate(String value) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            return LocalDate.parse(normalized);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private BusinessRuntimeException parameterError(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }
}
