package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.common.constants.BookingStatus;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.domain.entity.BookingRecord;
import com.fons.cloud.ai.trip.domain.service.BookingRecordDomainService;
import com.fons.cloud.common.result.R;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 外部预订记录查询工具
 *
 * <p>面向用户查询其通过 Agent 下单的机票/酒店/火车票等预订记录（{@code booking_record} 表），
 * 支持按内部预订单号精确查询，或按业务类型 / 预订状态过滤列表。数据始终以会话上下文中的
 * userId做租户隔离，避免越权查询他人订单。</p>
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BookingReadTools {
    public static final List<String> TOOLS = List.of("query_booking_record");

    private final BookingRecordDomainService bookingRecordDomainService;

    @Tool(name = "query_booking_record",
            description = "查询当前用户的预订记录。booking_id 优先精确查询，其余参数不参与筛选，但传入的枚举参数仍须为合法值。"
                    + "未传 booking_id 时，可按 travel_order_id、biz_type、status 组合筛选；均不传则查询全部记录。无匹配时返回成功和空列表。")
    R<List<BookingRecord>> queryBookingRecord(RuntimeContext context,
                                              @ToolParam(name = "booking_id", description = "内部预订单号，可选；传入后优先精确查询，其他条件不参与筛选", required = false) String bookingId,
                                              @ToolParam(name = "travel_order_id", description = "差旅单ID，可选；未传 booking_id 时筛选该行程关联的预订", required = false) String travelOrderId,
                                              @ToolParam(name = "biz_type", description = "业务类型：FLIGHT(机票)/HOTEL(酒店)/TRAIN(火车票)，可选", required = false) String bizType,
                                              @ToolParam(name = "status", description = "预订状态：CREATED/PENDING_PAYMENT/PAID/CONFIRMED/COMPLETED/CANCELLED/REFUNDED/FAILED，可选", required = false) String status) {
        String userId = context.getUserId();
        log.info("[TOOL][query_booking_record] userId={}, booking_id={}, travel_order_id={}, biz_type={}, status={}", userId, bookingId, travelOrderId, bizType, status);
        if (StringUtils.isBlank(userId)) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "user_id 不能为空");
        }

        BookingType bookingType = null;
        if (StringUtils.isNotBlank(bizType)) {
            bookingType = BookingType.of(bizType.trim().toUpperCase(Locale.ROOT));
            if (bookingType == null) {
                return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "无效的业务类型参数: " + bizType + "，业务类型项不能为空，可选值: FLIGHT/HOTEL/TRAIN");
            }
        }

        BookingStatus bookingStatus;
        if (StringUtils.isNotBlank(status)) {
            bookingStatus = BookingStatus.of(status.trim().toUpperCase(Locale.ROOT));
            if (bookingStatus == null) {
                return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "无效的状态参数: " + status + "，状态项不能为空，可选值: CREATED/PENDING_PAYMENT/PAID/CONFIRMED/COMPLETED/CANCELLED/REFUNDED/FAILED");
            }
        } else {
            bookingStatus = null;
        }


        if (StringUtils.isNotBlank(bookingId)) {
            // 根据预定ID和用户ID查找
            BookingRecord record = bookingRecordDomainService.findByIdAndUser(bookingId, userId);
            if (record == null) {
                return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "根据内部预订单号没有找到预定记录", List.of());
            }
            return R.success(TripAgentToolResultCode.SUCCESS, List.of(record));
        }

        if (StringUtils.isNotBlank(travelOrderId)) {
            // 根据差旅单查询
            List<BookingRecord> records = bookingRecordDomainService.findByUserIdAndTravelOrderId(userId, travelOrderId, bookingType);
            if (bookingStatus != null) {
                records = records.stream().filter(e -> e.getStatus() == bookingStatus).toList();
            }
            if (CollectionUtils.isEmpty(records)) {
                return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "根据差旅单没有找到预定记录", List.of());
            }
            return R.success(TripAgentToolResultCode.SUCCESS, records);
        }

        // 根据用户ID查询所有的预订记录
        List<BookingRecord> records = bookingRecordDomainService.findByUserIdAndBizType(userId, bookingType);
        if (bookingStatus != null) {
            records = records.stream().filter(e -> e.getStatus() == bookingStatus).toList();
        }
        if (CollectionUtils.isEmpty(records)) {
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), "根据用户ID没有找到预定记录", List.of());
        }
        return R.success(TripAgentToolResultCode.SUCCESS, records);

    }


}
