package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.application.itinerary.ItineraryPlanQueryApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.response.ItineraryCandidatesResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 行程规划只读工具集，读取当前用户、当前会话已经保存的累计候选和完整规划结果。
 * 工具不触发供应商搜索、规划计算或审核，也不向模型暴露候选存储Key和可信身份字段。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryPlanReadTools implements BaseTool {

    public static final List<String> TOOLS = List.of("query_itinerary_candidates", "query_itinerary_plan");

    private final ItineraryPlanQueryApplicationService itineraryPlanQueryApplicationService;

    @Tool(name = "query_itinerary_candidates",
            description = "读取当前会话中与指定往返条件完全匹配的累计去程、返程和酒店候选。"
                    + "此工具不发起新搜索；仅在需要按真实候选ID生成偏好分、明确排除项或重新查看累计候选时调用。"
                    + "成人数和儿童年龄必须与酒店搜索条件一致，否则会读取另一个候选分组。")
    public R<ItineraryCandidatesResult> queryItineraryCandidates(
            RuntimeContext context,
            @ToolParam(name = "origin", description = "出发城市，须与交通搜索条件一致") String origin,
            @ToolParam(name = "destination", description = "目的城市，须与交通及酒店搜索条件一致") String destination,
            @ToolParam(name = "departure_date", description = "去程日期及酒店入住日期，YYYY-MM-DD") String departureDate,
            @ToolParam(name = "return_date", description = "返程日期及酒店离店日期，YYYY-MM-DD，必须晚于去程日期") String returnDate,
            @ToolParam(name = "adult_count", description = "酒店搜索使用的成人数；不传默认2人", required = false) Integer adultCount,
            @ToolParam(name = "child_ages", description = "酒店搜索使用的儿童年龄列表；没有儿童时不传或传空列表", required = false) List<Integer> childAges) {
        CandidateOwner owner = candidateOwner(context);
        log.info("[TOOL][query_itinerary_candidates] userId={}, conversationId={}, origin={}, destination={}, "
                        + "departureDate={}, returnDate={}",
                owner.userId(), owner.conversationId(), origin, destination, departureDate, returnDate);

        ParsedDates dates = parseDates(departureDate, returnDate);
        if (dates.error() != null) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), dates.error());
        }
        try {
            ItineraryCandidatesResult result = itineraryPlanQueryApplicationService.getCandidates(
                    owner, origin, destination, dates.departureDate(), dates.returnDate(), adultCount, childAges);
            String message = "累计候选读取完成：去程" + result.outbound().size()
                    + "个、返程" + result.inbound().size() + "个、酒店及房型报价" + result.hotels().size() + "个。";
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, result);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][query_itinerary_candidates] 候选读取未完成，userId={}, code={}", owner.userId(), e.getCode());
            return R.failed(resolveBusinessErrorCode(e).getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][query_itinerary_candidates] 候选读取失败，userId={}", owner.userId(), e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(),
                    "累计候选读取失败，不能据此判断没有可用候选，请稍后重试。");
        }
    }

    @Tool(name = "query_itinerary_plan",
            description = "使用plan_itinerary返回的planId读取当前会话已保存的完整规划结果，"
                    + "包括交通、酒店、费用、评分和已识别风险。此工具不重新计算、不执行审核；不得猜测planId。")
    public R<ItineraryPlanningResult> queryItineraryPlan(
            RuntimeContext context,
            @ToolParam(name = "plan_id", description = "plan_itinerary成功返回的planId") String planId) {
        CandidateOwner owner = candidateOwner(context);
        String normalizedPlanId = StringUtils.trimToNull(planId);
        if (normalizedPlanId == null) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "plan_id 不能为空");
        }
        log.info("[TOOL][query_itinerary_plan] userId={}, conversationId={}, planId={}",
                owner.userId(), owner.conversationId(), normalizedPlanId);

        try {
            ItineraryPlanningResult result = itineraryPlanQueryApplicationService.getPlan(owner, normalizedPlanId);
            if (result == null) {
                return R.failed(TripAgentToolResultCode.PLAN_NOT_FOUND.getCode(),
                        "当前会话未找到对应的行程规划结果，请使用plan_itinerary实际返回的planId。");
            }
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "完整行程规划结果读取成功。", result);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][query_itinerary_plan] 规划结果读取未完成，userId={}, planId={}, code={}",
                    owner.userId(), normalizedPlanId, e.getCode());
            return R.failed(resolveBusinessErrorCode(e).getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][query_itinerary_plan] 规划结果读取失败，userId={}, planId={}", owner.userId(), normalizedPlanId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(),
                    "行程规划结果读取失败，不能据此判断规划结果不存在，请稍后重试。");
        }
    }

    private ParsedDates parseDates(String departureDate, String returnDate) {
        String normalizedDepartureDate = StringUtils.trimToNull(departureDate);
        String normalizedReturnDate = StringUtils.trimToNull(returnDate);
        if (normalizedDepartureDate == null || normalizedReturnDate == null) {
            return ParsedDates.failed("departure_date 和 return_date 不能为空");
        }
        try {
            LocalDate departure = LocalDate.parse(normalizedDepartureDate);
            LocalDate inbound = LocalDate.parse(normalizedReturnDate);
            if (!inbound.isAfter(departure)) {
                return ParsedDates.failed("return_date 必须晚于 departure_date");
            }
            return ParsedDates.success(departure, inbound);
        } catch (DateTimeParseException e) {
            return ParsedDates.failed("departure_date 和 return_date 必须为YYYY-MM-DD格式的有效日期");
        }
    }

    private CandidateOwner candidateOwner(RuntimeContext context) {
        String userId = context == null ? null : StringUtils.trimToNull(context.getUserId());
        String conversationId = context == null ? null : StringUtils.trimToNull(context.getSessionId());
        if (userId == null) {
            throw parameterError("运行时上下文缺少user_id");
        }
        if (conversationId == null) {
            throw parameterError("运行时上下文缺少conversation_id");
        }
        return new CandidateOwner(userId, conversationId);
    }

    private TripAgentToolResultCode resolveBusinessErrorCode(BusinessRuntimeException e) {
        return ResultCode.PARAMS_ERROR.getCode().equals(e.getCode())
                ? TripAgentToolResultCode.INVALID_PARAM : TripAgentToolResultCode.INTERNAL_ERROR;
    }

    private BusinessRuntimeException parameterError(String message) {
        return BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), message);
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }

    private record ParsedDates(LocalDate departureDate, LocalDate returnDate, String error) {

        private static ParsedDates success(LocalDate departureDate, LocalDate returnDate) {
            return new ParsedDates(departureDate, returnDate, null);
        }

        private static ParsedDates failed(String error) {
            return new ParsedDates(null, null, error);
        }
    }
}
