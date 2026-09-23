package com.fons.cloud.ai.trip.application.itinerary;

import com.alibaba.fastjson2.JSON;
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
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TravelOrderReference;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryCandidateRepository;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanRepository;
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

    private final ItineraryCandidateRepository candidateRepository;
    private final ItineraryPlanRepository planRepository;
    private final TravelOrderDomainService travelOrderDomainService;

    /**
     * 生成并保存一次单目的城市往返行程规划结果。
     * 候选数据由服务内部按照可信用户、会话及行程条件从候选仓储读取，
     * 不接收调用方提交的候选明细；差旅政策应由可信工具适配层查询后写入请求。
     *
     * @param request 行程规划请求，包含可信身份、明确行程条件、真实政策、偏好评分及排除项
     * @return 完整结构化规划结果；包含实际输入快照、计算指标、代表方案和风险信息
     */
    public R<ItineraryPlanningResult> plan(ItineraryPlanRequest request) {
        // 1. 校验参数
        if (request == null) {
            return R.failed(ResultCode.PARAMS_ERROR.getCode(), "行程规划请求不能为空");
        }
        List<String> validate = request.normalizeAndValidate();
        if (CollectionUtils.isNotEmpty(validate)) {
            return R.failed(ResultCode.PARAMS_ERROR.getCode(), String.join("；", validate));
        }

        log.info("ItineraryPlanApplicationService#plan: request={}", JSON.toJSONString(request));

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
        log.info("ItineraryPlanApplicationService#plan: planId={}, proposalCount={}", result.getPlanId(), result.getProposals().size());
        return R.success(result);
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
