package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.agent.model.TripTimeContext;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.response.CheckTravelTimeValidityResult;
import com.fons.cloud.ai.trip.common.response.QueryTravelApprovalResult;
import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.service.ApprovalRecordDomainService;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.ai.trip.infrastructure.config.TripTimeContextConfiguration;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 差旅单查询工具集， 提供不同场景下差旅单查询
 *
 * @author hongqy
 */
@Slf4j
@Component
public class TravelOrderReadTools {
    public static final List<String> TOOLS = List.of("query_travel_order", "query_travel_order_by_order_id", "query_travel_orders", "query_approval_status", "check_travel_time_validity");

    private final TravelOrderDomainService travelOrderDomainService;
    private final ApprovalRecordDomainService approvalRecordDomainService;
    private final Clock clock;

    public TravelOrderReadTools(TravelOrderDomainService travelOrderDomainService,
            ApprovalRecordDomainService approvalRecordDomainService,
            @Qualifier(TripTimeContextConfiguration.TRIP_AGENT_CLOCK) Clock clock) {
        this.travelOrderDomainService = travelOrderDomainService;
        this.approvalRecordDomainService = approvalRecordDomainService;
        this.clock = clock;
    }

    @Tool(name = "query_travel_order", description = "按出发城市、目的地和出发日期查询当前用户的差旅单候选列表，包含各状态。多条匹配时先让用户选择目标；无匹配时返回成功和空列表。")
    public R<List<TravelOrder>> queryTravelOrder(RuntimeContext context,
                                                 @ToolParam(name = "origin", description = "出发地城市，如'上海'") String origin,
                                                 @ToolParam(name = "destination", description = "目的地城市，如'杭州'") String destination,
                                                 @ToolParam(name = "departure_date", description = "出发日期，格式 YYYY-MM-DD") String departureDate) {
        String userId = context.getUserId();
        log.info("[TOOL][query_travel_order] userId={}, origin={}, destination={}, departureDate={}", userId, origin, destination, departureDate);

        // 参数校验
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        if (StringUtils.isBlank(origin) || StringUtils.isBlank(destination) || StringUtils.isBlank(departureDate)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "origin、destination 和 departure_date 不能为空");
        }
        String dateError = validateDateRange(departureDate, null);
        if (dateError != null) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), dateError);
        }

        // 行程信息不唯一，返回全部候选单据供用户确定目标
        List<TravelOrder> orders = travelOrderDomainService.findByTravelDetailInfo(userId, origin, destination, departureDate);
        if (CollectionUtils.isEmpty(orders)) {
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "未找到符合条件的差旅单", List.of());
        }
        return R.success(TripAgentToolResultCode.SUCCESS.getCode(), TripAgentToolResultCode.SUCCESS.getMessage(), orders);
    }


    @Tool(name = "query_travel_order_by_order_id", description = "根据差旅单号，查询指定差旅单详情。")
    public R<TravelOrder> queryTravelOrderByOrderId(RuntimeContext context, @ToolParam(name = "order_id", description = "差旅单ID") String orderId) {
        String userId = context.getUserId();
        log.info("[TOOL][query_travel_order_by_order_id] userId={}, orderId={}", userId, orderId);

        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        if (StringUtils.isBlank(orderId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "order_id 不能为空");
        }

        TravelOrder existOrder = travelOrderDomainService.findByOrderIdAndUserId(orderId, userId);
        if (existOrder == null) {
            return R.failed(TripAgentToolResultCode.ORDER_NOT_FOUND);
        }
        return R.success(TripAgentToolResultCode.SUCCESS.getCode(), TripAgentToolResultCode.SUCCESS.getMessage(), existOrder);
    }


    @Tool(name = "query_travel_orders", description = "查询当前用户的差旅单列表，支持按状态、出发日期范围过滤，边界包含当天。不传过滤条件时查询全部状态；无匹配时返回成功和空列表。")
    public R<List<TravelOrder>> queryTravelOrders(
            RuntimeContext context,
            @ToolParam(name = "status", description = "状态过滤，多个值用逗号分隔，可含空格但不能有空项。可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED", required = false) String status,
            @ToolParam(name = "start_date", description = "筛选出发日期不早于此日期的差旅单，格式 YYYY-MM-DD，可选", required = false) String startDate,
            @ToolParam(name = "end_date", description = "筛选出发日期不晚于此日期的差旅单，格式 YYYY-MM-DD，可选", required = false) String endDate) {

        String userId = context.getUserId();
        log.info("[TOOL][query_travel_orders] userId={}, status={}, start_date={}, end_date={}", userId, status, startDate, endDate);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }

        List<TravelOrderStatus> inputStatusList = new ArrayList<>();

        // 参数校验
        if (StringUtils.isNotBlank(status)) {
            for (String statusStr : status.split(",", -1)) {
                TravelOrderStatus code = TravelOrderStatus.findByCode(statusStr.trim());
                if (code == null) {
                    return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "无效的状态参数: " + status + "，状态项不能为空，可选值: DRAFT/SUBMITTED/APPROVED/REJECTED/COMPLETED/CANCELLED");
                } else if (!inputStatusList.contains(code)) {
                    inputStatusList.add(code);
                }
            }
        }

        String dateError = validateDateRange(startDate, endDate);
        if (dateError != null) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), dateError);
        }

        List<TravelOrder> orders = travelOrderDomainService.findByStatusAndUserIdAndDateRange(userId, inputStatusList, startDate, endDate);
        if (CollectionUtils.isEmpty(orders)) {
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "未找到符合条件的差旅单", List.of());
        }
        return R.success(TripAgentToolResultCode.SUCCESS.getCode(), TripAgentToolResultCode.SUCCESS.getMessage(), orders);
    }


    @Tool(name = "query_approval_status", description = "查询差旅审批状态。若提供审批实例ID则按ID查询；否则按用户ID查询其最近的审批单")
    public R<QueryTravelApprovalResult> queryApprovalStatus(
            RuntimeContext context,
            @ToolParam(name = "process_instance_id", description = "审批实例ID，可选", required = false) String processInstanceId) {

        String userId = context.getUserId();
        log.info("[TOOL][query_approval_status] userId={}, processInstanceId={}", userId, processInstanceId);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }

        String queryMode;
        ApprovalRecord record;
        if (StringUtils.isNotBlank(processInstanceId)) {
            queryMode = "by_instance";
            record = approvalRecordDomainService.findByIdAndUserId(processInstanceId, userId);
        } else {
            queryMode = "latest";
            record = approvalRecordDomainService.findLatestByUserId(userId);
        }

        if (record == null) {
            return R.failed(TripAgentToolResultCode.RECORD_NOT_FOUND, QueryTravelApprovalResult.of(queryMode));
        }
        return R.success(TripAgentToolResultCode.SUCCESS.getCode(), TripAgentToolResultCode.SUCCESS.getMessage(), QueryTravelApprovalResult.of(queryMode, record));
    }


    @Tool(name = "check_travel_time_validity", description = "行程规划前检查当前用户指定差旅单的日期，不校验审批状态。"
            + "data.valid=false表示行程已开始或已结束，应停止规划；未找到对应申请时，时间检查允许直接规划，不代表申请存在或已审批。")
    public R<CheckTravelTimeValidityResult> checkTravelTimeValidity(RuntimeContext context,
            @ToolParam(name = "order_id", description = "已确定的目标差旅申请单号，不是审批单号或预订单号。") String orderId) {
        String userId = context.getUserId();
        // 优先沿用本轮模型日期；独立调用工具时也使用配置的业务时区。
        TripTimeContext timeContext = context.get(TripTimeContext.class);
        LocalDate today = timeContext == null ? LocalDate.now(clock) : timeContext.currentDate();
        log.info("[TOOL][check_travel_time_validity] userId={}, orderId={}, today={}", userId, orderId, today);

        // 参数校验
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        if (StringUtils.isBlank(orderId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "order_id 不能为空");
        }

        // 根据差旅单号和用户归属查询，不复用旧会话缓存
        TravelOrder order = travelOrderDomainService.findByOrderIdAndUserId(orderId, userId);
        if (order == null) {
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "未找到对应差旅单，本次时间检查允许直接规划行程，不代表申请存在或已审批。",
                    new CheckTravelTimeValidityResult(true, today.toString(), List.of()));
        }

        if (StringUtils.isBlank(order.getDepartureDate()) || StringUtils.isBlank(order.getReturnDate())) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), "差旅单出发日期或返回日期缺失，无法确认行程时间是否有效。");
        }

        LocalDate departureDate;
        LocalDate returnDate;
        try {
            departureDate = LocalDate.parse(order.getDepartureDate());
            returnDate = LocalDate.parse(order.getReturnDate());
        } catch (DateTimeParseException e) {
            log.warn("[TOOL][check_travel_time_validity] 差旅单日期解析失败，orderId:{}", orderId, e);
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), "差旅单日期无效，无法确认行程时间是否有效。");
        }
        if (departureDate.isAfter(returnDate)) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), "差旅单出发日期不能晚于返回日期。");
        }

        // 先检查是否已结束，再检查是否已开始，出发当天也视为已开始
        String blockReason = null;
        if (today.isAfter(returnDate)) {
            blockReason = "行程已结束（返回日期 " + order.getReturnDate() + " 已过）";
        } else if (!today.isBefore(departureDate)) {
            blockReason = "行程已开始（出发日期 " + order.getDepartureDate() + " 已到达或已过）";
        }

        if (blockReason != null) {
            CheckTravelTimeValidityResult.BlockedOrder blockedOrder = new CheckTravelTimeValidityResult.BlockedOrder(
                    order.getOrderId(), order.getDestination(), order.getDepartureDate(), order.getReturnDate(), blockReason);
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "检测到" + blockReason + "的差旅单，无法进行规划，请告知用户并停止规划。",
                    new CheckTravelTimeValidityResult(false, today.toString(), List.of(blockedOrder)));
        }

        return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "差旅单日期有效，可继续规划。",
                new CheckTravelTimeValidityResult(true, today.toString(), List.of()));
    }

    /**
     * 校验可选日期边界，空边界不参与比较；不自动修正无效日历日期。
     */
    private String validateDateRange(String startDate, String endDate) {
        LocalDate start = null;
        LocalDate end = null;
        try {
            if (StringUtils.isNotBlank(startDate)) {
                if (startDate.length() != 10) {
                    return "日期必须使用 YYYY-MM-DD 格式";
                }
                start = LocalDate.parse(startDate);
            }
            if (StringUtils.isNotBlank(endDate)) {
                if (endDate.length() != 10) {
                    return "日期必须使用 YYYY-MM-DD 格式";
                }
                end = LocalDate.parse(endDate);
            }
        } catch (DateTimeParseException e) {
            return "日期必须是有效日历日期，格式为 YYYY-MM-DD";
        }
        if (start != null && end != null && start.isAfter(end)) {
            return "开始日期不能晚于结束日期";
        }
        return null;
    }
}
