package com.fons.cloud.ai.trip.agent.tool;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateTime;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.application.TravelOrderApplicationService;
import com.fons.cloud.ai.trip.common.constants.OrderStatus;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.request.TravelOrderCreateRequest;
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
import java.util.regex.Pattern;

/**
 * 差旅单全生命周期工具集：提交审批、查询审批状态、取消出差申请、修改出差申请。
 * 多步写操作统一委托给 TravelOrderService 在事务内执行，本类只负责参数校验、幂等/状态检查与 JSON 包装。
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TravelOrderWriteTools {
    private static final Pattern DATE_PATTERN = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");
    public static final List<String> TOOLS = List.of("submit_travel_approval");

    private final TravelOrderApplicationService travelOrderApplicationService;


    /**
     * 提交审批（同时创建差旅单 + 审批单，双向关联）
     * @param context
     * @param destination
     * @param departureCity
     * @param departureDate
     * @param returnDate
     * @param purpose
     * @return
     */
    @Tool(name = "submit_travel_approval", description = "一次性创建差旅申请单（差旅单）和审批单，并将两者双向关联。" + "执行步骤：① 根据出行信息生成差旅单（DRAFT）；" + "② 提交审批单（PENDING）并绑定差旅单ID；" + "③ 将差旅单状态更新为 SUBMITTED 并写入审批单ID。" + "返回差旅单ID（orderId）和审批单ID（processInstanceId）。" + "日期字段必须为 YYYY-MM-DD 格式（例如 2026-07-15），否则会返回参数错误。")
    public R<SubmitTravelApprovalResult> submitTravelApproval(RuntimeContext context,
                                                              @ToolParam(name = "destination", description = "目的地城市") String destination,
                                                              @ToolParam(name = "departure_city", description = "出发城市") String departureCity,
                                                              @ToolParam(name = "departure_date", description = "出发日期，必须为 YYYY-MM-DD 格式，例如 2026-07-15") String departureDate,
                                                              @ToolParam(name = "return_date", description = "返回日期，必须为 YYYY-MM-DD 格式，例如 2026-07-18") String returnDate,
                                                              @ToolParam(name = "purpose", description = "出差事由") String purpose
                                       ) {
        String userId = context.getUserId();
        log.info("[TOOL][submit_travel_approval] userId={}, destination={}, departureDate={}, returnDate={}", userId, destination, departureDate, returnDate);

        // 参数校验
        List<String> errors = submitTravelParamsValid(destination, departureCity, departureDate, returnDate, purpose, userId);
        if (CollectionUtils.isNotEmpty(errors)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), StringUtils.join(errors,";"));
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

            // 这里进行事务回查防止写库失败
            SubmitTravelApprovalResult approvalResult = result.getData();
            R<Void> valid = travelOrderApplicationService.verifyOrderStatus(approvalResult.orderId(), OrderStatus.SUBMITTED);
            if (!valid.isSuccess()) {
                // 写操作已经执行但是事务回查失败
                log.warn("[TOOL][submit_travel_approval]差旅单校验失败， code：{}, message:{}", result.getCode(), result.getMessage());
                return R.failed(TripAgentToolResultCode.VERIFY_FAILED.getCode(), StrUtil.format("操作已执行但验证不通过, orderId={}, cause:{}", approvalResult.orderId(), valid.getMessage()));
            }

            if (result.isDuplicateSuccess()) {
                // 幂等成功 差旅单已经存在 则返回幂等成功的语义
                return R.success(TripAgentToolResultCode.SUCCESS.getCode(),
                        StrUtil.format("检测到相同出行信息的差旅申请已存在（差旅单ID: {}），已直接返回该申请，未重复创建。", approvalResult.orderId()), approvalResult);
            } else {
                // 差旅单已经创建成功
                return R.success(TripAgentToolResultCode.SUCCESS.getCode(),
                        StrUtil.format("差旅申请已创建并提交审批。差旅单ID: {}，审批单ID: {}，当前状态: 待审批。", approvalResult.orderId(), approvalResult.processInstanceId()), approvalResult);
            }
        } catch (Exception e) {
            log.warn("[TOOL][submit_travel_approval]执行异常， userId:{}", userId, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "创建差旅单失败: " + e.getMessage());
        }
    }


    private List<String> submitTravelParamsValid(String destination, String departureCity, String departureDate, String returnDate, String purpose, String userId) {
        List<String> errorParams = new ArrayList<>();
        if (StringUtils.isBlank(userId)) {
            errorParams.add("userId不能为空");
        }
        if (StringUtils.isBlank(destination)) {
            errorParams.add("destination（目的地城市）不能为空");
        }
        if (StringUtils.isBlank(departureCity)) {
            errorParams.add("departure_city（出发城市）不能为空");
        }
        if (StringUtils.isBlank(purpose)) {
            errorParams.add("purpose（出差事由）不能为空");
        }

        String departureDateErr = validateDate(departureDate, "departure_date（出发日期）");
        if (StringUtils.isNotBlank(departureDateErr)) {
            errorParams.add(departureDateErr);
        }
        String returnDateErr = validateDate(returnDate, "return_date（返回日期）");
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
        if (!DATE_PATTERN.matcher(date).matches()) {
            return fieldName + " 格式错误，必须为 YYYY-MM-DD（如 2026-07-15），实际值为：" + date;
        }
        try {
            LocalDate.parse(date);
            return null;
        } catch (DateTimeParseException e) {
            return fieldName + " 不是有效的日期，必须为 YYYY-MM-DD，实际值为：" + date;
        }
    }






}
