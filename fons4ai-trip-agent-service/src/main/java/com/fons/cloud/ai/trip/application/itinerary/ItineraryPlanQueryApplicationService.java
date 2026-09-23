package com.fons.cloud.ai.trip.application.itinerary;

import cn.hutool.core.lang.Assert;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateGroups;
import com.fons.cloud.ai.trip.common.dto.ItineraryCandidateSnapshot;
import com.fons.cloud.ai.trip.common.response.ItineraryCandidatesResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryCandidateRepository;
import com.fons.cloud.ai.trip.infrastructure.repository.ItineraryPlanRepository;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;

/**
 * 行程规划只读应用服务，按可信归属读取累计候选和完整规划结果。
 * 不执行供应商搜索、规划计算、审核或页面渲染。
 *
 * @author hongqy
 */
@Service
@RequiredArgsConstructor
public class ItineraryPlanQueryApplicationService {

    private static final int DEFAULT_ADULT_COUNT = 2;

    private final ItineraryCandidateRepository candidateRepository;
    private final ItineraryPlanRepository planRepository;

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
        return new ItineraryCandidatesResult(snapshot.capturedAt(), snapshot.outboundTransports(),
                snapshot.inboundTransports(), snapshot.hotels().candidates());
    }

    /**
     * 按可信归属及planId读取完整规划结果，不回退到其他用户、会话或最近一次方案。
     */
    public ItineraryPlanningResult getPlan(CandidateOwner owner, String planId) {
        Assert.notNull(owner, () -> parameterError("规划结果归属不能为空"));
        Assert.notBlank(planId, () -> parameterError("planId不能为空"));
        return planRepository.findById(owner, planId.trim());
    }

    private BusinessRuntimeException parameterError(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }
}
