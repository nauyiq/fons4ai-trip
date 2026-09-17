package com.fons.cloud.ai.trip.agent.tool;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.application.BookingApplicationService;
import com.fons.cloud.ai.trip.application.TravelOrderApplicationService;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.BookingSummary;
import com.fons.cloud.ai.trip.common.dto.CancelOderOutcome;
import com.fons.cloud.ai.trip.common.request.TravelOrderCancelRequest;
import com.fons.cloud.ai.trip.common.request.TravelOrderCreateRequest;
import com.fons.cloud.ai.trip.common.request.TravelOrderModifyRequest;
import com.fons.cloud.ai.trip.common.response.CancelTravelApprovalResult;
import com.fons.cloud.ai.trip.common.response.ModifyTravelApprovalResult;
import com.fons.cloud.ai.trip.common.response.SubmitTravelApprovalResult;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 差旅单全生命周期工具集：提交审批、取消出差申请、修改出差申请。
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TravelOrderWriteTools {
    public static final List<String> TOOLS = List.of("submit_travel_approval", "cancel_travel_order", "modify_travel_order");

    private final TravelOrderApplicationService travelOrderApplicationService;
    private final BookingApplicationService bookingApplicationService;

    @Tool(name = "submit_travel_approval", description = "用户确认完整出行摘要后，为当前用户创建差旅申请并提交业务审批，提交成功不代表审批通过。"
            + "出发城市、目的地及出发和返回日期匹配已有生效申请时，返回已有记录，不重复创建。"
            + "返回申请、审批信息及实际状态，不执行外部预订。")
    public R<SubmitTravelApprovalResult> submitTravelApproval(RuntimeContext context,
                                                              @ToolParam(name = "destination", description = "用户确认的目的地城市名称，不能为空。") String destination,
                                                              @ToolParam(name = "departure_city", description = "用户确认的出发城市名称，不能为空。") String departureCity,
                                                              @ToolParam(name = "departure_date", description = "有效出发日期，YYYY-MM-DD格式，不得晚于返回日期。") String departureDate,
                                                              @ToolParam(name = "return_date", description = "有效返回日期，YYYY-MM-DD格式，不得早于出发日期。") String returnDate,
                                                              @ToolParam(name = "purpose", description = "用户确认的真实出差事由，不能为空。") String purpose) {
        String userId = context.getUserId();
        log.info("[TOOL][submit_travel_approval] userId={}, destination={}, departureDate={}, returnDate={}", userId, destination, departureDate, returnDate);

        // 参数校验
        List<String> errors = travelParamsValid(destination, departureCity, departureDate, returnDate, purpose, userId, false);
        if (CollectionUtils.isNotEmpty(errors)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), StringUtils.join(errors, ";"));
        }

        // 预定日期校验
        DateTime departureDatetime = DateUtil.parse(departureDate, DatePattern.NORM_DATE_PATTERN);
        DateTime returnDatetime = DateUtil.parse(returnDate, DatePattern.NORM_DATE_PATTERN);
        if (departureDatetime.isAfter(returnDatetime)) {
            return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), "出发日期（" + departureDate + "）不能晚于返回日期（" + returnDate + "）");
        }

        try {
            // 调用应用层服务发起差旅单的创建
            R<SubmitTravelApprovalResult> result = travelOrderApplicationService.createTravelOrder(TravelOrderCreateRequest.builder()
                    .userId(userId)
                    .destination(destination)
                    .departureCity(departureCity)
                    .departureDate(departureDate)
                    .returnDate(returnDate)
                    .purpose(purpose).build());

            if (!result.isSuccess()) {
                // 差旅单创建失败 这里返回失败给到LLM
                log.warn("[TOOL][submit_travel_approval]创建差旅单失败， code：{}, message:{}", result.getCode(), result.getMessage());
                return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "创建差旅申请单失败: " + result.getMessage());
            }

            SubmitTravelApprovalResult approvalResult = result.getData();
            if (result.isDuplicateSuccess()) {
                // 已有申请保留真实状态，不按新提交申请强制验证SUBMITTED
                return R.success(TripAgentToolResultCode.SUCCESS.getCode(),
                        StrUtil.format("检测到相同出行信息的差旅申请已存在（差旅单ID: {}），已直接返回该申请，未重复创建。当前状态以返回数据为准。", approvalResult.orderId()), approvalResult);
            }

            // 新提交申请回查SUBMITTED状态，确认事务已落库
            R<Void> valid = travelOrderApplicationService.verifyOrderStatus(approvalResult.orderId(), TravelOrderStatus.SUBMITTED);
            if (!valid.isSuccess()) {
                // 写操作已经执行但是事务回查失败
                log.warn("[TOOL][submit_travel_approval]差旅单校验失败， code：{}, message:{}", valid.getCode(), valid.getMessage());
                return R.failed(TripAgentToolResultCode.VERIFY_FAILED.getCode(), StrUtil.format("操作已执行但验证不通过, orderId={}, cause:{}", approvalResult.orderId(), valid.getMessage()));
            }

            return R.success(TripAgentToolResultCode.SUCCESS.getCode(),
                    StrUtil.format("差旅申请已创建并提交审批。差旅单ID: {}，审批单ID: {}，当前状态: 待审批。", approvalResult.orderId(), approvalResult.processInstanceId()), approvalResult);
        } catch (Exception e) {
            log.warn("[TOOL][submit_travel_approval]执行异常， userId:{}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "创建差旅单失败: " + e.getMessage());
        }
    }


    @Tool(name = "cancel_travel_order", description = "用户确认目标及影响后，取消当前用户的指定差旅申请，并撤销存在且尚未取消的关联审批。"
            + "已取消或已完成的申请不可取消；已审批通过的申请需二次确认。"
            + "不取消外部预订，返回实际审批处理结果和关联预订提示。")
    public R<CancelTravelApprovalResult> cancelTravelOrder(RuntimeContext context,
                                                           @ToolParam(name = "order_id", description = "已确定的目标差旅申请单号，不是审批单号或预订单号。") String travelOrderId,
                                                           @ToolParam(name = "reason", description = "用户提供的取消原因，可省略。", required = false) String reason,
                                                           @ToolParam(name = "force", description = "已审批申请的二次确认标记，默认false；仅在用户明确确认当前目标的取消影响后传true。", required = false) Boolean force) {
        String userId = context.getUserId();
        log.info("[TOOL][cancel_travel_order] userId={}, travelOrderId={}, force={}", userId, travelOrderId, force);

        // 参数校验
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }
        if (StringUtils.isBlank(travelOrderId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "order_id 不能为空");
        }

        try {
            // 调用应用层服务发起差旅单的取消
            R<CancelOderOutcome> cancelResult = travelOrderApplicationService.cancelTravelOrder(TravelOrderCancelRequest.builder()
                    .userId(userId)
                    .oderId(travelOrderId)
                    .reason(reason)
                    .force(Boolean.TRUE.equals(force)).build());
            if (!cancelResult.isSuccess()) {
                // 审批单取消失败 根据业务码进行判断 响应给LLM不同的错误信息
                String code = cancelResult.getCode();
                String message = cancelResult.getMessage();
                log.warn("[TOOL][cancel_travel_order]差旅单取消失败, code:{}, message:{}", code, message);

                if (TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST.getCode().equals(code)) {
                    return R.failed(TripAgentToolResultCode.ORDER_NOT_FOUND.getCode(), "差旅单不存在：" + travelOrderId);
                }
                if (TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_SUPPORT_CANCEL.getCode().equals(code)) {
                    return R.failed(TripAgentToolResultCode.INVALID_STATE.getCode(), "差旅单取消失败, cause:" + message);
                }
                if (TripAgentResultCode.TRAVEL_ORDER_CANCEL_NEED_USER_SECOND_CONFIRM.getCode().equals(code)) {
                    return R.failed(TripAgentToolResultCode.NEED_USER_CONFIRM.getCode(), "该差旅单已审批通过，取消将撤销关联审批并可能影响已预订行程，请用户明确确认后继续（传入 force=true）。");
                }
                // 未知异常返回一个内部异常
                return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), message);
            }

            CancelOderOutcome cancelOderOutcome = cancelResult.getData();
            log.info("[TOOL][cancel_travel_order] 差旅单取消结果：{}", JSON.toJSONString(cancelOderOutcome));
            if (!cancelOderOutcome.orderCancelled()) {
                return R.failed(TripAgentToolResultCode.UPDATED_FAILED.getCode(), "差旅单状态更新失败，请稍后重试。");
            }

            // 这里进行事务回查防止写库失败
            R<Void> valid = travelOrderApplicationService.verifyOrderStatus(travelOrderId, TravelOrderStatus.CANCELLED);
            if (!valid.isSuccess()) {
                // 写操作已经执行但是事务回查失败
                log.warn("[TOOL][cancel_travel_order]差旅单校验失败， code：{}, message:{}", valid.getCode(), valid.getMessage());
                return R.failed(TripAgentToolResultCode.VERIFY_FAILED.getCode(), StrUtil.format("操作已执行但验证不通过, travelOrderId={}, cause:{}", travelOrderId, valid.getMessage()));
            }

            // 查询关联预定记录 让用户可以知道已经预定了什么 并且是不是需要取消预定
            AssociatedBookings associatedBookings = queryAssociatedBookings(userId, travelOrderId);
            String message = cancelOderOutcome.approvalCancelled() ? "差旅申请已取消，关联审批单已撤销。" : "差旅申请已取消。";
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, new CancelTravelApprovalResult(
                    travelOrderId, TravelOrderStatus.CANCELLED.getCode(), cancelOderOutcome.approvalCancelled(), cancelOderOutcome.cancelledApprovalId(),
                    reason, associatedBookings.bookings(), associatedBookings.message()));
        } catch (Exception e) {
            log.error("[TOOL][cancel_travel_order] 执行异常， userId:{}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), e.getMessage());
        }
    }


    @Tool(name = "modify_travel_order", description = "用户确认修改内容及影响后，修改当前用户的指定差旅申请；仅提供需变更字段，省略或空白保留原值，不支持清空。"
            + "有变化时撤销尚未取消的旧审批并提交新审批；无变化时返回现有记录，不撤审重提。"
            + "已取消或已完成的申请不可修改；已审批通过的申请需二次确认。"
            + "不修改外部预订，返回实际修改、审批结果和关联预订提示。")
    public R<ModifyTravelApprovalResult> modifyTravelOrder(RuntimeContext context,
                                                           @ToolParam(name = "order_id", description = "已确定的目标差旅申请单号，不是审批单号或预订单号。") String orderId,
                                                           @ToolParam(name = "destination", description = "新的目的地城市名称，省略或空白保留原值。", required = false) String destination,
                                                           @ToolParam(name = "departure_city", description = "新的出发城市名称，省略或空白保留原值。", required = false) String departureCity,
                                                           @ToolParam(name = "departure_date", description = "新的有效出发日期，YYYY-MM-DD格式；省略保留原值，结合新旧值不得晚于返回日期。", required = false) String departureDate,
                                                           @ToolParam(name = "return_date", description = "新的有效返回日期，YYYY-MM-DD格式；省略保留原值，结合新旧值不得早于出发日期。", required = false) String returnDate,
                                                           @ToolParam(name = "purpose", description = "新的出差事由，省略或空白保留原值。", required = false) String purpose,
                                                           @ToolParam(name = "force", description = "已审批申请的二次确认标记，默认false；仅在用户明确确认当前目标及本次撤审重提的影响后传true。", required = false) Boolean force) {

        String userId = context.getUserId();
        log.info("[TOOL][modify_travel_order] userId={}, orderId={}, force={}", userId, orderId, force);

        // 参数校验
        if (StringUtils.isBlank(orderId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "order_id 不能为空");
        }
        List<String> errors = travelParamsValid(destination, departureCity, departureDate, returnDate, purpose, userId, true);
        if (CollectionUtils.isNotEmpty(errors)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), StringUtils.join(errors, ";"));
        }

        try {
            // 调用应用层服务修改差旅单、撤销旧审批并重新提交
            R<ModifyTravelApprovalResult> modifyResult = travelOrderApplicationService.modifyTravelOrder(TravelOrderModifyRequest.builder()
                    .userId(userId)
                    .orderId(orderId)
                    .destination(destination)
                    .departureCity(departureCity)
                    .departureDate(departureDate)
                    .returnDate(returnDate)
                    .purpose(purpose)
                    .force(Boolean.TRUE.equals(force))
                    .build());

            if (!modifyResult.isSuccess()) {
                // 根据业务码返回不同的工具错误，便于LLM判断后续处理方式
                String code = modifyResult.getCode();
                String message = modifyResult.getMessage();
                log.warn("[TOOL][modify_travel_order]差旅单修改失败, code:{}, message:{}", code, message);

                if (TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST.getCode().equals(code)) {
                    return R.failed(TripAgentToolResultCode.ORDER_NOT_FOUND.getCode(), "差旅单不存在：" + orderId);
                }
                if (TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_SUPPORT_MODIFY.getCode().equals(code)) {
                    return R.failed(TripAgentToolResultCode.INVALID_STATE.getCode(), "差旅单修改失败, cause:" + message);
                }
                if (TripAgentResultCode.TRAVEL_ORDER_MODIFY_NEED_USER_SECOND_CONFIRM.getCode().equals(code)) {
                    return R.failed(TripAgentToolResultCode.NEED_USER_CONFIRM.getCode(), "该差旅单已审批通过，修改将撤销当前审批并重新发起新流程，请用户明确确认后继续（传入 force=true）。");
                }
                if (TripAgentResultCode.TRAVEL_ORDER_INVALID_DATE_RANGE.getCode().equals(code)) {
                    return R.failed(TripAgentToolResultCode.INVALID_DATE_RANGE.getCode(), message);
                }
                return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), message);
            }

            ModifyTravelApprovalResult approvalResult = modifyResult.getData();
            log.info("[TOOL][modify_travel_order] 差旅单修改结果：{}", JSON.toJSONString(approvalResult));
            if (modifyResult.isDuplicateSuccess()) {
                // 无字段变更时保留当前状态，不撤销审批、不重新提交
                return R.success(TripAgentToolResultCode.SUCCESS.getCode(), approvalResult.message(), approvalResult);
            }

            // 事务提交后回查差旅单状态，防止写库失败
            R<Void> valid = travelOrderApplicationService.verifyOrderStatus(orderId, TravelOrderStatus.SUBMITTED);
            if (!valid.isSuccess()) {
                log.warn("[TOOL][modify_travel_order]差旅单校验失败， code：{}, message:{}", valid.getCode(), valid.getMessage());
                return R.failed(TripAgentToolResultCode.VERIFY_FAILED.getCode(), StrUtil.format("操作已执行但验证不通过, orderId={}, cause:{}", orderId, valid.getMessage()));
            }

            // 查询关联预订，提醒用户是否需要调整机票、酒店或火车票
            AssociatedBookings associatedBookings = queryAssociatedBookings(userId, orderId);
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), approvalResult.message(), new ModifyTravelApprovalResult(
                    approvalResult.orderId(), approvalResult.orderStatus(), approvalResult.oldApprovalId(),
                    approvalResult.oldApprovalCancelled(), approvalResult.newApprovalId(), approvalResult.newApprovalStatus(),
                    approvalResult.updatedFields(), approvalResult.message(), associatedBookings.bookings(), associatedBookings.message()));
        } catch (Exception e) {
            log.error("[TOOL][modify_travel_order] 执行异常， userId:{}, orderId:{}", userId, orderId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "修改出差申请失败：" + e.getMessage());
        }
    }


    private List<String> travelParamsValid(String destination, String departureCity, String departureDate, String returnDate, String purpose, String userId, boolean isModify) {
        List<String> errorParams = new ArrayList<>();
        if (StringUtils.isBlank(userId)) {
            errorParams.add("userId不能为空");
        }
        if (!isModify && StringUtils.isBlank(destination)) {
            errorParams.add("destination（目的地城市）不能为空");
        }
        if (!isModify && StringUtils.isBlank(departureCity)) {
            errorParams.add("departure_city（出发城市）不能为空");
        }
        if (!isModify && StringUtils.isBlank(purpose)) {
            errorParams.add("purpose（出差事由）不能为空");
        }

        String departureDateErr = !isModify || StringUtils.isNotBlank(departureDate)
                ? validateDate(departureDate, "departure_date（出发日期）") : null;
        if (StringUtils.isNotBlank(departureDateErr)) {
            errorParams.add(departureDateErr);
        }
        String returnDateErr = !isModify || StringUtils.isNotBlank(returnDate)
                ? validateDate(returnDate, "return_date（返回日期）") : null;
        if (StringUtils.isNotBlank(returnDateErr)) {
            errorParams.add(returnDateErr);
        }
        return errorParams;
    }


    /**
     * 校验日期字段格式（YYYY-MM-DD）。
     *
     * @return null 表示合法；否则返回错误描述
     */
    private static String validateDate(String date, String fieldName) {
        if (date == null || date.isBlank()) {
            return fieldName + " 不能为空";
        }
        if (date.length() != 10) {
            return fieldName + " 格式错误，必须为 YYYY-MM-DD（如 2026-07-15），实际值为：" + date;
        }
        try {
            LocalDate.parse(date);
            return null;
        } catch (DateTimeParseException e) {
            return fieldName + " 不是有效的日期，必须为 YYYY-MM-DD，实际值为：" + date;
        }
    }

    /**
     * 写操作已成功后的附加查询，查询失败不覆盖写操作成功结果。
     */
    private AssociatedBookings queryAssociatedBookings(String userId, String orderId) {
        try {
            List<BookingSummary> bookings = bookingApplicationService.queryAssociatedBookings(userId, orderId);
            return new AssociatedBookings(bookings, buildAffectedBookingsMessage(bookings));
        } catch (Exception e) {
            log.warn("[TOOL][query_associated_bookings] 查询关联预订失败，userId:{}, orderId:{}", userId, orderId, e);
            return new AssociatedBookings(null, "差旅申请操作已成功，但关联预订查询失败，尚不能确认是否存在需要取消或调整的预订，请勿重复执行申请操作。");
        }
    }

    private record AssociatedBookings(List<BookingSummary> bookings, String message) {
    }

    /**
     * 根据关联预订生成提示信息，引导 LLM 告知用户受影响的预订
     */
    private String buildAffectedBookingsMessage(List<BookingSummary> bookings) {
        if (bookings == null || bookings.isEmpty()) {
            return null;
        }
        long flights = bookings.stream().filter(b -> BookingType.FLIGHT.getCode().equals(b.bizType())).count();
        long hotels = bookings.stream().filter(b -> BookingType.HOTEL.getCode().equals(b.bizType())).count();
        long trains = bookings.stream().filter(b -> BookingType.TRAIN.getCode().equals(b.bizType())).count();
        StringBuilder sb = new StringBuilder();
        sb.append("该行程存在 ").append(bookings.size()).append(" 条关联预订（");
        List<String> parts = new ArrayList<>();
        if (flights > 0) {
            parts.add("机票" + flights + "张");
        }
        if (hotels > 0) {
            parts.add("酒店" + hotels + "间");
        }
        if (trains > 0) {
            parts.add("火车票" + trains + "张");
        }
        sb.append(String.join("、", parts));
        sb.append("），请提醒用户是否需要取消或调整这些预订。");
        return sb.toString();
    }


}
