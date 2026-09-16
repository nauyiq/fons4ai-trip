package com.fons.cloud.ai.trip.application;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.common.constants.OrderStatus;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.CancelOderOutcome;
import com.fons.cloud.ai.trip.common.request.TravelOrderCancelRequest;
import com.fons.cloud.ai.trip.common.request.TravelOrderCreateRequest;
import com.fons.cloud.ai.trip.common.response.CancelTravelApprovalResult;
import com.fons.cloud.ai.trip.common.response.SubmitTravelApprovalResult;
import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.service.ApprovalRecordDomainService;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 差旅订单应用服务
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelOrderApplicationService {
    private final TransactionTemplate transactionTemplate;
    private final TravelOrderDomainService travelOrderDomainService;
    private final ApprovalRecordDomainService approvalRecordDomainService;

    /**
     * 创建差旅单和审批记录
     * @param request
     * @return
     */
    public R<SubmitTravelApprovalResult> createTravelOrder(TravelOrderCreateRequest request) {
        log.info("【创建差旅单请求】 request:{}", JSON.toJSONString(request));
        TravelOrder existOrder = findDuplicateTravelOrder(request);
        if (existOrder != null) {
            // 已存在差旅单， 构造幂等成功的结果返回
            ApprovalRecord existRecord = approvalRecordDomainService.getById(existOrder.getApprovalId());
            Assert.notNull(existRecord, () -> BusinessRuntimeException.of(TripAgentResultCode.APPROVAL_RECORD_NOT_EXIST));
            return R.duplicateSuccess(buildApprovalResult(existOrder, existRecord));
        } else {
            // 创建差旅单
            TravelOrder order = TravelOrder.create(request);
            ApprovalRecord approvalRecord = transactionTemplate.execute(status -> {
                try {
                    // 1. 新增差旅单
                    Assert.isTrue(travelOrderDomainService.save(order), () -> SystemIntervalException.of("新增差旅单失败"));
                    log.info("1. 差旅单已创建, orderId:{}", order.getOrderId());
                    // 2. 提交审批单
                    ApprovalRecord record = approvalRecordDomainService.submit(order);
                    Assert.notNull(record, () -> SystemIntervalException.of("提交审批单失败"));
                    log.info("2. 审批单已经创建， processInstanceId={}", record.getProcessInstanceId());
                    // 3. 关联审批单
                    order.submitted(record.getProcessInstanceId());
                    Assert.isTrue(travelOrderDomainService.saveOrUpdate(order), () -> SystemIntervalException.of("差旅单关联审批单失败"));
                    log.info("3. 差旅单关联审批单成功， 差旅单={}, 审批单{}", order.getOrderId(), order.getApprovalId());
                    return record;
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                    status.setRollbackOnly();
                    return null;
                }
            });

            if (approvalRecord == null) {
                return R.failed(ResultCode.SYSTEM_BUSY);
            }
            return R.success(buildApprovalResult(order, approvalRecord));
        }
    }

    /**
     * 取消差旅单和审批单
     * @param request
     * @return
     */
    public R<CancelOderOutcome> cancelTravelOrder(TravelOrderCancelRequest request) {
        log.info("【取消差旅单请求】 request:{}", JSON.toJSONString(request));
        TravelOrder existOrder = travelOrderDomainService.findByOrderIdAndUserId(request.getUserId(), request.getOderId());
        if (existOrder == null) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST);
        }

        // 状态判断
        OrderStatus orderStatus = existOrder.getStatus();
        if (orderStatus == OrderStatus.CANCELLED) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_SUPPORT_CANCEL.getCode(), "差旅单已取消，不可重复取消");
        }
        if (orderStatus == OrderStatus.COMPLETED) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_SUPPORT_CANCEL.getCode(), "差旅单已完成，不可取消");
        }
        if (orderStatus == OrderStatus.APPROVED && !request.getForce()) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_CANCEL_NEED_USER_SECOND_CONFIRM.getCode(), "该差旅单已审批通过，取消后不可恢复且可能影响已预订行程。需要用户二次确认");
        }

        CancelOderOutcome outcome = travelOrderDomainService.cancelWithApproval(existOrder, request.getReason());
        return R.ok(outcome);
    }


    /**
     * 查库验证差旅单状态，确保写操作真正落库成功。
     * @param orderId
     * @param status
     * @return
     */
    public R<Void> verifyOrderStatus(String orderId, OrderStatus status) {
        TravelOrder travelOrder = travelOrderDomainService.getById(orderId);
        if (travelOrder == null) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST);
        }
        if (travelOrder.getStatus() != status) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_EXPECTED.getCode(), StrUtil.format("差旅单期望状态 {}， 实际状态 {}", status.getCode(), travelOrder.getStatus().getCode()));
        }
        return R.success();
    }



    private TravelOrder findDuplicateTravelOrder(TravelOrderCreateRequest request) {
        // 查找是否存在同一个差旅单
        List<TravelOrder> activeOrders = travelOrderDomainService.findActiveByUserIdAndDateRange(
                request.getUserId(),
                List.of(OrderStatus.DRAFT, OrderStatus.SUBMITTED, OrderStatus.APPROVED),
                request.getDepartureDate(),
                request.getReturnDate());
        List<TravelOrder> sameOrders = activeOrders.stream().filter(o -> o.getDepartureCity().equals(request.getDepartureCity()) && o.getDestination().equals(request.getDestination())
                && o.getDepartureDate().equals(request.getDepartureDate()) && o.getReturnDate().equals(request.getReturnDate())).toList();
        if (CollectionUtils.isEmpty(activeOrders)) {
            return null;
        }
        return sameOrders.getFirst();
    }

    private SubmitTravelApprovalResult buildApprovalResult(TravelOrder order, ApprovalRecord record) {
        return new SubmitTravelApprovalResult(order.getOrderId(), record.getProcessInstanceId(),
                order.getStatus().getCode(), record.getStatus().getCode(), DateUtil.format(record.getCreated(), DatePattern.NORM_DATE_PATTERN),
                order.getDestination(), order.getDepartureCity(), order.getDepartureDate(), order.getReturnDate(), order.getPurpose());
    }
}
