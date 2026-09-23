package com.fons.cloud.ai.trip.agent.tool;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.application.business.TravelPolicyApplicationService;
import com.fons.cloud.ai.trip.application.itinerary.ItineraryPlanApplicationService;
import com.fons.cloud.ai.trip.application.itinerary.ItineraryReviewApplicationService;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.CandidateOwner;
import com.fons.cloud.ai.trip.common.dto.TravelPolicy;
import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest;
import com.fons.cloud.ai.trip.common.request.ItineraryPlanRequest.CandidatePreferenceScores;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult;
import com.fons.cloud.ai.trip.common.response.PlanItineraryResult;
import com.fons.cloud.ai.trip.common.response.PlanItineraryResult.ProposalSummary;
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
 * 往返行程规划及审核工具（去程 + 住宿 + 返程）的Agent适配入口。
 *
 * <p>设计原则：
 * <ul>
 *   <li>用户和会话来自运行时上下文，差旅政策由服务端查询，不接受模型填写。</li>
 *   <li>工具只解析Agent参数、调用应用服务并返回结果，组合、评分、保存和审核由应用服务承接。</li>
 *   <li>工具不推测用户偏好；有明确偏好时，候选偏好分由模型通过scores提交。</li>
 * </ul>
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryPlannerTools implements BaseTool {

    public static final List<String> TOOLS = List.of("plan_itinerary", "review_itinerary_plan");

    private final ItineraryPlanApplicationService itineraryPlanApplicationService;
    private final ItineraryReviewApplicationService itineraryReviewApplicationService;
    private final TravelPolicyApplicationService travelPolicyApplicationService;

    @Tool(
            name = "plan_itinerary",
            description = "根据当前会话已经保存的去程、酒店和返程候选生成往返方案。"
                    + "工具会按当前用户和目的城市读取真实差旅政策，完成费用、耗时、偏好、政策和体验计算，"
                    + "保存完整结果并返回代表方案摘要。调用前必须完成对应候选搜索；工具不解读偏好文本，"
                    + "有明确偏好时应先通过scores提供候选偏好分。"
    )
    public R<PlanItineraryResult> planItinerary(
            RuntimeContext context,
            @ToolParam(name = "origin", description = "出发城市，必填，如上海") String origin,
            @ToolParam(name = "destination", description = "目的城市，必填，如杭州") String destination,
            @ToolParam(name = "departure_date", description = "去程日期，YYYY-MM-DD") String departureDate,
            @ToolParam(name = "return_date", description = "返程日期，YYYY-MM-DD，必须晚于去程日期") String returnDate,
            @ToolParam(name = "travel_order_id", description = "基于已确定差旅单规划时传入其单号；服务端会校验当前用户归属，不关联差旅单时不传", required = false) String travelOrderId,
            @ToolParam(name = "adult_count", description = "酒店搜索使用的成人数；不传默认2人", required = false) Integer adultCount,
            @ToolParam(name = "child_ages", description = "酒店搜索使用的儿童年龄列表；没有儿童时不传或传空列表", required = false) List<Integer> childAges,
            @ToolParam(name = "preferences", description = "用户明确表达或长期记忆召回的偏好，无则留空；工具不自行解读", required = false) String preferences,
            @ToolParam(name = "scores",
                    description = "候选偏好分JSON：{transport_scores:{候选ID:{score:0-100,basis:[理由]}},"
                            + "hotel_scores:{候选ID:{score:0-100,basis:[理由]}}}。ID必须来自搜索结果；"
                            + "留空或传auto时所有候选使用中性分50，提供JSON后未评分候选按0分处理。",
                    required = false) String scores,
            @ToolParam(name = "weather_summary", description = "已查询到的出发地和目的地天气摘要，用于体验评分；无真实天气数据时留空", required = false) String weatherSummary,
            @ToolParam(name = "excluded_candidate_ids", description = "需要明确排除的交通或酒店候选ID列表；不排除时不传或传空列表", required = false) List<String> excludedCandidateIds) {
        String userId = context == null ? null : context.getUserId();
        String conversationId = context == null ? null : context.getSessionId();
        String normalizedUserId = requiredRuntimeValue(userId, "user_id");
        String normalizedConversationId = requiredRuntimeValue(conversationId, "conversation_id");
        log.info("[TOOL][plan_itinerary] userId={}, conversationId={}, origin={}, destination={}, "
                        + "departureDate={}, returnDate={}",
                normalizedUserId, normalizedConversationId, origin, destination, departureDate, returnDate);

        ParsedParameter<LocalDate> parsedDepartureDate = parseDate(departureDate, "departure_date");
        if (parsedDepartureDate.invalid()) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), parsedDepartureDate.error());
        }
        ParsedParameter<LocalDate> parsedReturnDate = parseDate(returnDate, "return_date");
        if (parsedReturnDate.invalid()) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), parsedReturnDate.error());
        }
        ParsedParameter<CandidatePreferenceScores> parsedScores = parseScores(scores);
        if (parsedScores.invalid()) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), parsedScores.error());
        }

        ItineraryPlanRequest request = ItineraryPlanRequest.builder()
                .userId(normalizedUserId)
                .conversationId(normalizedConversationId)
                .travelOrderId(travelOrderId)
                .origin(origin)
                .destination(destination)
                .departureDate(parsedDepartureDate.value())
                .returnDate(parsedReturnDate.value())
                .adultCount(adultCount)
                .childAges(childAges)
                .preferences(preferences)
                .scores(parsedScores.value())
                .weatherSummary(weatherSummary)
                .excludedCandidateIds(excludedCandidateIds)
                .build();
        List<String> validationErrors = request.normalizeAndValidate();
        if (!validationErrors.isEmpty()) {
            return requestValidationFailure(validationErrors);
        }

        try {
            TravelPolicy policy = travelPolicyApplicationService.getPolicy(normalizedUserId, request.getDestination());
            request.setPolicy(policy);

            R<ItineraryPlanningResult> planningResult = itineraryPlanApplicationService.plan(request);
            if (!planningResult.isSuccess()) {
                return planningFailure(planningResult);
            }
            ItineraryPlanningResult result = planningResult.getData();
            if (result == null || result.getProposals() == null || result.getProposals().isEmpty()) {
                log.error("[TOOL][plan_itinerary] 规划成功响应缺少代表方案，userId={}", normalizedUserId);
                return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "行程规划未返回有效方案，请重新搜索候选后再试。");
            }

            PlanItineraryResult summary = toSummary(result);
            String message = "行程规划完成，已生成" + summary.proposalCount() + "个代表方案；完整方案已保存，请使用planId继续审核和展示。";
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, summary);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][plan_itinerary] 规划请求未完成，userId={}, code={}", userId, e.getCode());
            return R.failed(resolveBusinessErrorCode(e).getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][plan_itinerary] 行程规划失败，userId={}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "行程规划失败，不能据此判断没有可用方案，请稍后重试。");
        }
    }

    @Tool(
            name = "review_itinerary_plan",
            description = "审核当前会话中plan_itinerary已经保存的指定方案，重新读取关联差旅单当前状态，"
                    + "执行差旅单一致性、政策合规和方案执行可行性审核。"
                    + "工具调用成功不等于审核通过；必须根据返回的executionStatus、verdict、"
                    + "recommendedProposalId、issues、remediationItems和nextAction继续处理。"
    )
    public R<ItineraryReviewResult> reviewItineraryPlan(
            RuntimeContext context,
            @ToolParam(name = "plan_id", description = "plan_itinerary成功返回的真实planId") String planId) {
        String userId = context == null ? null : context.getUserId();
        String conversationId = context == null ? null : context.getSessionId();
        String normalizedUserId = requiredRuntimeValue(userId, "user_id");
        String normalizedConversationId = requiredRuntimeValue(conversationId, "conversation_id");
        String normalizedPlanId = StringUtils.trimToNull(planId);
        if (normalizedPlanId == null) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "plan_id 不能为空");
        }

        log.info("[TOOL][review_itinerary_plan] userId={}, conversationId={}, planId={}",
                normalizedUserId, normalizedConversationId, normalizedPlanId);
        try {
            CandidateOwner owner = new CandidateOwner(normalizedUserId, normalizedConversationId);
            ItineraryReviewResult result = itineraryReviewApplicationService.review(owner, normalizedPlanId);
            if (result == null) {
                return R.failed(TripAgentToolResultCode.PLAN_NOT_FOUND.getCode(),
                        "当前会话未找到对应的行程规划结果，请使用plan_itinerary实际返回的planId。");
            }
            String message = "行程规划审核已执行：executionStatus=" + result.getExecutionStatus()
                    + "，verdict=" + result.getVerdict()
                    + "，nextAction=" + result.getNextAction()
                    + "。请依据结构化审核结果继续处理。";
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, result);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][review_itinerary_plan] 审核请求未完成，userId={}, planId={}, code={}",
                    normalizedUserId, normalizedPlanId, e.getCode());
            return R.failed(resolveBusinessErrorCode(e).getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][review_itinerary_plan] 行程规划审核失败，userId={}, planId={}",
                    normalizedUserId, normalizedPlanId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(),
                    "行程规划审核失败，不能据此判断方案已通过审核，请稍后重试。");
        }
    }

    private ParsedParameter<CandidatePreferenceScores> parseScores(String scores) {
        String normalized = StringUtils.trimToNull(scores);
        if (normalized == null || "auto".equalsIgnoreCase(normalized)) {
            return ParsedParameter.success(null);
        }
        try {
            CandidatePreferenceScores result = JSON.parseObject(normalized, CandidatePreferenceScores.class);
            if (result == null) {
                return ParsedParameter.failed("scores 不能为null，请提供合法JSON或传auto");
            }
            return ParsedParameter.success(result);
        } catch (JSONException e) {
            return ParsedParameter.failed("scores 必须是合法的候选偏好分JSON");
        }
    }

    private ParsedParameter<LocalDate> parseDate(String value, String parameterName) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            return ParsedParameter.failed(parameterName + " 不能为空");
        }
        try {
            return ParsedParameter.success(LocalDate.parse(normalized));
        } catch (DateTimeParseException e) {
            return ParsedParameter.failed(parameterName + " 必须为YYYY-MM-DD格式的有效日期");
        }
    }

    private String requiredRuntimeValue(String value, String parameterName) {
        String normalized = StringUtils.trimToNull(value);
        if (normalized == null) {
            throw BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(),
                    "运行时上下文缺少" + parameterName);
        }
        return normalized;
    }

    private R<PlanItineraryResult> requestValidationFailure(List<String> validationErrors) {
        boolean dateErrorsOnly = validationErrors.stream().allMatch(message -> message.contains("日期"));
        TripAgentToolResultCode resultCode = dateErrorsOnly
                ? TripAgentToolResultCode.INVALID_DATE_RANGE : TripAgentToolResultCode.INVALID_PARAM;
        return R.failed(resultCode.getCode(), String.join("；", validationErrors));
    }

    private R<PlanItineraryResult> planningFailure(R<ItineraryPlanningResult> planningResult) {
        TripAgentToolResultCode resultCode;
        if (ResultCode.PARAMS_ERROR.getCode().equals(planningResult.getCode())) {
            resultCode = TripAgentToolResultCode.INVALID_PARAM;
        } else if (ResultCode.INVALID_DATA.getCode().equals(planningResult.getCode())) {
            resultCode = TripAgentToolResultCode.VERIFY_FAILED;
        } else if (TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST.getCode().equals(planningResult.getCode())) {
            resultCode = TripAgentToolResultCode.ORDER_NOT_FOUND;
        } else {
            resultCode = TripAgentToolResultCode.INTERNAL_ERROR;
        }
        return R.failed(resultCode.getCode(), planningResult.getMessage());
    }

    private PlanItineraryResult toSummary(ItineraryPlanningResult result) {
        List<ProposalSummary> proposals = result.getProposals().stream()
                .map(this::toProposalSummary)
                .toList();
        return new PlanItineraryResult(result.getPlanId(), result.getCombinationCount(), proposals.size(), proposals);
    }

    private ProposalSummary toProposalSummary(Proposal proposal) {
        return new ProposalSummary(proposal.proposalId(), proposal.tags(), proposal.scores().overall());
    }

    private TripAgentToolResultCode resolveBusinessErrorCode(BusinessRuntimeException e) {
        return ResultCode.PARAMS_ERROR.getCode().equals(e.getCode())
                ? TripAgentToolResultCode.INVALID_PARAM : TripAgentToolResultCode.INTERNAL_ERROR;
    }

    private record ParsedParameter<T>(T value, String error) {

        private static <T> ParsedParameter<T> success(T value) {
            return new ParsedParameter<>(value, null);
        }

        private static <T> ParsedParameter<T> failed(String error) {
            return new ParsedParameter<>(null, error);
        }

        private boolean invalid() {
            return error != null;
        }
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }
}
