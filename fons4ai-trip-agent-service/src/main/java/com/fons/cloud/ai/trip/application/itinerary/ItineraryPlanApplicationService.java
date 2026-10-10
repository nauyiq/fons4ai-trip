package com.fons.cloud.ai.trip.application.itinerary;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.application.itinerary.validator.ItineraryRepairCycleValidatorService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateGroups;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateSnapshot;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateSnapshot.PreparationResult;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateSnapshot.SelectedCandidates;
import com.fons.cloud.ai.trip.common.dto.ItineraryPlanCalculation;
import com.fons.cloud.ai.trip.common.dto.ItineraryPlanCombination;
import com.fons.cloud.ai.trip.common.dto.ItineraryPlanCombination.BuildResult;
import com.fons.cloud.ai.trip.common.dto.ItineraryPlanningCandidates;
import com.fons.cloud.ai.trip.common.dto.ItineraryPlanningCandidates.ConversionResult;
import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryCandidatesResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TravelOrderReference;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryCandidateRepository;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanRepository;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 行程规划应用服务，基于已保存的标准候选、可信差旅政策和偏好评分生成结构化方案。
 * 本服务不调用供应商搜索、不解析Agent工具字符串参数，也不负责审核和页面渲染。
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ItineraryPlanApplicationService {
    private static final int DEFAULT_ADULT_COUNT = 2;

    private final TravelOrderDomainService travelOrderDomainService;
    private final ItineraryCandidateRepository candidateRepository;
    private final ItineraryPlanRepository planRepository;
    private final ItineraryRepairCycleValidatorService repairCycleService;

    /**
     * 生成并保存一次单目的城市往返行程规划结果。
     * 候选数据由服务内部按照可信用户、会话及行程条件从候选仓储读取，
     * 不接收调用方提交的候选明细；差旅政策应由可信工具适配层查询后写入请求。
     *
     * @param request 行程规划请求，包含可信身份、明确行程条件、真实政策、偏好评分及排除项
     * @param runId   运行时提供的本轮标识，用于区分修复与下一轮新任务
     * @return 完整结构化规划结果；包含实际输入快照、计算指标、代表方案和风险信息
     */
    public R<ItineraryPlanningResult> plan(ItineraryPlanRequest request, String runId) {
        // 1. 校验参数
        if (request == null) {
            return R.failed(ResultCode.PARAMS_ERROR.getCode(), "行程规划请求不能为空");
        }
        List<String> validationErrors = request.normalizeAndValidate();
        if (CollectionUtils.isNotEmpty(validationErrors)) {
            return R.failed(ResultCode.PARAMS_ERROR.getCode(), String.join("；", validationErrors));
        }
        repairCycleService.beforePlan(new CandidateOwner(request.getUserId(), request.getConversationId()), request, runId);

        log.info("ItineraryPlanApplicationService#plan: userId={}, conversationId={}, origin={}, "
                        + "destination={}, departureDate={}, returnDate={}, travelOrderLinked={}",
                request.getUserId(), request.getConversationId(), request.getOrigin(),
                request.getDestination(), request.getDepartureDate(), request.getReturnDate(),
                request.getTravelOrderId() != null);

        // 2. 验证可选差旅单关联，模型提供的单号不能直接作为审核事实
        CandidateOwner owner = new CandidateOwner(request.getUserId(), request.getConversationId());
        R<TravelOrderReference> travelOrderResult = resolveTravelOrderReference(request);
        if (!travelOrderResult.isSuccess()) {
            return R.failed(travelOrderResult.getCode(), travelOrderResult.getMessage());
        }

        // 3. 按可信用户、会话和行程条件读取本次规划候选快照
        ItineraryCandidateSnapshot candidateSnapshot = loadCandidateSnapshot(request, owner);
        // 4. 校验候选引用并应用明确排除项
        PreparationResult preparation = candidateSnapshot.prepareForPlanning(
                request.getScores(), request.getExcludedCandidateIds());
        if (CollectionUtils.isNotEmpty(preparation.dataErrors())) {
            return R.failed(ResultCode.INVALID_DATA.getCode(), String.join("；", preparation.dataErrors()));
        }
        if (CollectionUtils.isNotEmpty(preparation.referenceErrors())) {
            return R.failed(ResultCode.PARAMS_ERROR.getCode(), String.join("；", preparation.referenceErrors()));
        }
        SelectedCandidates selectedCandidates = preparation.candidates();

        // 5. 过滤无法参与客观计算的候选，不使用默认值掩盖时间或报价缺失
        ConversionResult conversion = ItineraryPlanningCandidates.prepare(request, selectedCandidates);
        if (CollectionUtils.isNotEmpty(conversion.errors())) {
            return R.failed(ResultCode.INVALID_DATA.getCode(), String.join("；", conversion.errors()));
        }
        ItineraryPlanningCandidates planningCandidates = conversion.candidates();
        log.info("ItineraryPlanApplicationService#plan: candidateSnapshot capturedAt={}, "
                        + "outboundCount={}, inboundCount={}, hotelCount={}",
                candidateSnapshot.capturedAt(), planningCandidates.outbound().size(),
                planningCandidates.inbound().size(), planningCandidates.hotels().size());
        if (CollectionUtils.isNotEmpty(planningCandidates.rejectedCandidates())) {
            log.info("ItineraryPlanApplicationService#plan: rejectedCandidateCount={}",
                    planningCandidates.rejectedCandidates().size());
        }

        // 6. 生成时间顺序有效的往返组合并计算客观费用、耗时指标
        BuildResult buildResult = ItineraryPlanCombination.build(planningCandidates);
        if (CollectionUtils.isNotEmpty(buildResult.errors())) {
            return R.failed(ResultCode.INVALID_DATA.getCode(), String.join("；", buildResult.errors()));
        }
        List<ItineraryPlanCombination> combinations = buildResult.combinations();
        log.info("ItineraryPlanApplicationService#plan: combinationCount={}, rejectedCombinationCount={}",
                combinations.size(), buildResult.rejectedCombinationCount());

        // 7. 计算政策、偏好和体验分，补充可信差旅单快照后保存完整结果
        ItineraryPlanningResult result = ItineraryPlanCalculation.calculate(request, combinations);
        result.setSourceTravelOrder(travelOrderResult.getData());
        planRepository.save(owner, result);
        // 8. 规划保存成功后更新修复次数，失败的计算或保存不消耗修复机会
        repairCycleService.afterPlan(owner, request, runId, result);
        log.info("ItineraryPlanApplicationService#plan: planId={}, proposalCount={}", result.getPlanId(), result.getProposals().size());
        return R.success(result);
    }

    /**
     * 根据planId获取一次单目的城市往返行程规划结果
     * @param owner  规划结果归属不能为空
     * @param planId planId不能为空
     * @return
     */
    public ItineraryPlanningResult getPlan(CandidateOwner owner, String planId) {
        Assert.notNull(owner, () -> parameterError("规划结果归属不能为空"));
        Assert.notBlank(planId, () -> parameterError("planId不能为空"));
        return planRepository.findById(owner, planId.trim());
    }


    /**
     * 读取与指定往返条件完全匹配的累计候选池。
     */
    public ItineraryCandidatesResult getCandidates(CandidateOwner owner,
                                                   String origin,
                                                   String destination,
                                                   LocalDate departureDate,
                                                   LocalDate returnDate,
                                                   Integer adultCount,
                                                   List<Integer> childAges) {
        Assert.notNull(owner, () -> parameterError("候选归属不能为空"));
        int normalizedAdultCount = adultCount == null ? DEFAULT_ADULT_COUNT : adultCount;
        List<Integer> normalizedChildAges = childAges == null ? List.of() : childAges;
        ItineraryCandidateGroups groups = ItineraryCandidateGroups.roundTrip(owner, origin, destination,
                departureDate, returnDate, normalizedAdultCount, normalizedChildAges);
        ItineraryCandidateSnapshot snapshot = candidateRepository.loadSnapshot(groups);
        return new ItineraryCandidatesResult(snapshot.capturedAt(), snapshot.outboundTransports(), snapshot.inboundTransports(), snapshot.hotels().candidates());
    }



    private BusinessRuntimeException parameterError(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }

    /**
     * 构造本次往返规划需要的五个候选分组，并一次性读取固定快照。
     */
    private ItineraryCandidateSnapshot loadCandidateSnapshot(ItineraryPlanRequest request, CandidateOwner owner) {
        ItineraryCandidateGroups groups = ItineraryCandidateGroups.roundTrip(owner,
                request.getOrigin(), request.getDestination(), request.getDepartureDate(),
                request.getReturnDate(), request.getAdultCount(), request.getChildAges());
        return candidateRepository.loadSnapshot(groups);
    }

    /**
     * 按当前用户验证可选差旅单引用。模型只提供标识，服务端查询结果才是可信审核依据。
     */
    private R<TravelOrderReference> resolveTravelOrderReference(ItineraryPlanRequest request) {
        if (StringUtils.isBlank(request.getTravelOrderId())) {
            return R.success(null);
        }

        TravelOrder source = travelOrderDomainService.findByOrderIdAndUserId(
                request.getTravelOrderId(), request.getUserId());
        if (source == null) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST);
        }
        if (source.getStatus() == null || StringUtils.isAnyBlank(source.getOrderId(),
                source.getDepartureCity(), source.getDestination(), source.getDepartureDate(),
                source.getReturnDate())) {
            return R.failed(ResultCode.INVALID_DATA.getCode(),
                    "关联差旅单缺少单号、状态、城市或日期，无法建立可信规划关联");
        }
        try {
            TravelOrderReference travelOrder = toTravelOrderReference(source);
            if (travelOrder.returnDate().isBefore(travelOrder.departureDate())) {
                return R.failed(ResultCode.INVALID_DATA.getCode(), "关联差旅单的出发日期不能晚于返回日期");
            }
            return R.success(travelOrder);
        } catch (DateTimeParseException e) {
            return R.failed(ResultCode.INVALID_DATA.getCode(), "关联差旅单的出发日期或返回日期无效");
        }
    }

    private TravelOrderReference toTravelOrderReference(TravelOrder order) {
        return new TravelOrderReference(order.getOrderId(), order.getApprovalId(), order.getStatus(),
                StringUtils.trimToNull(order.getDepartureCity()), StringUtils.trimToNull(order.getDestination()),
                LocalDate.parse(order.getDepartureDate()), LocalDate.parse(order.getReturnDate()));
    }

}
