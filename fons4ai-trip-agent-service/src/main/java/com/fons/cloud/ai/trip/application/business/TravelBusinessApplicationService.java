package com.fons.cloud.ai.trip.application.business;

import cn.hutool.core.date.DatePattern;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.common.constants.ApprovalStatus;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.constants.TripAgentResultCode;
import com.fons.cloud.ai.trip.common.dto.CancelOderOutcome;
import com.fons.cloud.ai.trip.common.request.TravelOrderCancelRequest;
import com.fons.cloud.ai.trip.common.request.TravelOrderCreateRequest;
import com.fons.cloud.ai.trip.common.request.TravelOrderModifyRequest;
import com.fons.cloud.ai.trip.common.response.ModifyTravelApprovalResult;
import com.fons.cloud.ai.trip.common.response.SubmitTravelApprovalResult;
import com.fons.cloud.ai.trip.common.vo.TravelOrderView;
import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;
import com.fons.cloud.ai.trip.domain.entity.BookingRecord;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.mapper.TravelOrderItineraryPlanMapper;
import com.fons.cloud.ai.trip.domain.service.ApprovalRecordDomainService;
import com.fons.cloud.ai.trip.domain.service.BookingRecordDomainService;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 差旅业务 应用服务
 *
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TravelBusinessApplicationService {
    private final TransactionTemplate transactionTemplate;
    private final TravelOrderDomainService travelOrderDomainService;
    private final ApprovalRecordDomainService approvalRecordDomainService;
    private final BookingRecordDomainService bookingRecordDomainService;
    private final TravelOrderItineraryPlanMapper itineraryPlanMapper;

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
            ApprovalRecord existRecord = null;
            if (StringUtils.isNotBlank(existOrder.getApprovalId())) {
                existRecord = approvalRecordDomainService.getById(existOrder.getApprovalId());
                Assert.notNull(existRecord, () -> BusinessRuntimeException.of(TripAgentResultCode.APPROVAL_RECORD_NOT_EXIST));
            }
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
        TravelOrder existOrder = travelOrderDomainService.findByOrderIdAndUserId(request.getOderId(), request.getUserId());
        if (existOrder == null) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST);
        }

        // 状态判断
        TravelOrderStatus orderStatus = existOrder.getStatus();
        if (orderStatus == TravelOrderStatus.CANCELLED) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_SUPPORT_CANCEL.getCode(), "差旅单已取消，不可重复取消");
        }
        if (orderStatus == TravelOrderStatus.COMPLETED) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_SUPPORT_CANCEL.getCode(), "差旅单已完成，不可取消");
        }
        if (orderStatus == TravelOrderStatus.APPROVED && !Boolean.TRUE.equals(request.getForce())) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_CANCEL_NEED_USER_SECOND_CONFIRM.getCode(), "该差旅单已审批通过，取消后不可恢复且可能影响已预订行程。需要用户二次确认");
        }

        CancelOderOutcome outcome = travelOrderDomainService.cancelWithApproval(existOrder, request.getReason());
        return R.ok(outcome);
    }


    /**
     * 修改差旅单，事务内撤销旧审批并重新提交审批。
     * 未提供的字段保留原值，字段无变化时不撤销审批、不重新提交。
     * @param request 修改请求
     * @return 修改后的差旅单与审批信息
     */
    public R<ModifyTravelApprovalResult> modifyTravelOrder(TravelOrderModifyRequest request) {
        log.info("【修改差旅单请求】 request:{}", JSON.toJSONString(request));
        TravelOrder order = travelOrderDomainService.findByOrderIdAndUserId(request.getOrderId(), request.getUserId());
        if (order == null) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_NOT_EXIST);
        }

        // 查询时同时限定用户归属，禁止修改已取消或已完成的差旅单
        TravelOrderStatus orderStatus = order.getStatus();
        if (orderStatus == TravelOrderStatus.CANCELLED) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_SUPPORT_MODIFY.getCode(), "已取消的差旅单不可修改，请重新提交新的出差申请。");
        }
        if (orderStatus == TravelOrderStatus.COMPLETED) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_STATUS_NOT_SUPPORT_MODIFY.getCode(), "已完成的差旅单不可修改。");
        }
        if (orderStatus == TravelOrderStatus.APPROVED && !request.isForce()) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_MODIFY_NEED_USER_SECOND_CONFIRM.getCode(), "该差旅单已审批通过，修改将撤销当前审批并重新发起新流程，需要用户二次确认。");
        }

        // 仅修改一个日期时，仍需与保留的原日期组合校验
        String departureDate = StringUtils.isNotBlank(request.getDepartureDate()) ? request.getDepartureDate() : order.getDepartureDate();
        String returnDate = StringUtils.isNotBlank(request.getReturnDate()) ? request.getReturnDate() : order.getReturnDate();
        if (LocalDate.parse(departureDate).isAfter(LocalDate.parse(returnDate))) {
            return R.failed(TripAgentResultCode.TRAVEL_ORDER_INVALID_DATE_RANGE.getCode(),
                    StrUtil.format("出发日期（{}）不能晚于返回日期（{}）", departureDate, returnDate));
        }

        boolean noChange = (StringUtils.isBlank(request.getDestination()) || StringUtils.equals(request.getDestination(), order.getDestination()))
                && (StringUtils.isBlank(request.getDepartureCity()) || StringUtils.equals(request.getDepartureCity(), order.getDepartureCity()))
                && (StringUtils.isBlank(request.getDepartureDate()) || StringUtils.equals(request.getDepartureDate(), order.getDepartureDate()))
                && (StringUtils.isBlank(request.getReturnDate()) || StringUtils.equals(request.getReturnDate(), order.getReturnDate()))
                && (StringUtils.isBlank(request.getPurpose()) || StringUtils.equals(request.getPurpose(), order.getPurpose()));
        if (noChange) {
            log.info("【修改差旅单】无字段变更，跳过撤审重提，orderId:{}", order.getOrderId());
            return R.duplicateSuccess(new ModifyTravelApprovalResult(order.getOrderId(), orderStatus.getCode(), order.getApprovalId(), false, order.getApprovalId(),
                    null, "无字段变更", "提交的信息与当前差旅单一致，未做任何修改。", List.of(), null));
        }

        // 只有行程条件变化时，已发布规划才需要失效。
        boolean itineraryChanged = (StringUtils.isNotBlank(request.getDestination()) && !StringUtils.equals(request.getDestination(), order.getDestination()))
                || (StringUtils.isNotBlank(request.getDepartureCity()) && !StringUtils.equals(request.getDepartureCity(), order.getDepartureCity()))
                || (StringUtils.isNotBlank(request.getDepartureDate()) && !StringUtils.equals(request.getDepartureDate(), order.getDepartureDate()))
                || (StringUtils.isNotBlank(request.getReturnDate()) && !StringUtils.equals(request.getReturnDate(), order.getReturnDate()));
        String originalDestination = order.getDestination();
        String originalDepartureCity = order.getDepartureCity();
        String originalDepartureDate = order.getDepartureDate();
        String originalReturnDate = order.getReturnDate();
        String originalPurpose = order.getPurpose();

        // 仅更新用户提供的字段，汇总本次修改内容
        List<String> updatedFields = updatedOrderFields(request, order);

        // 撤销旧审批、保存变更、发起新审批和关联新审批必须原子执行
        String oldApprovalId = order.getApprovalId();
        ModifyTravelApprovalResult outcome = transactionTemplate.execute(status -> {
            try {
                // 修改和方案发布先锁同一主单；快照过期时拒绝覆盖其他请求的结果。
                TravelOrder current = travelOrderDomainService.findByOrderIdAndUserIdForUpdate(request.getOrderId(), request.getUserId());
                Assert.isTrue(current != null && current.getStatus() == orderStatus
                                && StringUtils.equals(current.getApprovalId(), oldApprovalId)
                                && StringUtils.equals(current.getDestination(), originalDestination)
                                && StringUtils.equals(current.getDepartureCity(), originalDepartureCity)
                                && StringUtils.equals(current.getDepartureDate(), originalDepartureDate)
                                && StringUtils.equals(current.getReturnDate(), originalReturnDate)
                                && StringUtils.equals(current.getPurpose(), originalPurpose),
                        () -> SystemIntervalException.of("差旅单已发生变化，请重新查询后修改"));
                boolean oldApprovalCancelled = false;
                if (StringUtils.isNotBlank(oldApprovalId)) {
                    ApprovalRecord oldRecord = approvalRecordDomainService.getById(oldApprovalId);
                    Assert.notNull(oldRecord, () -> BusinessRuntimeException.of(TripAgentResultCode.APPROVAL_RECORD_NOT_EXIST));
                    if (oldRecord.getStatus() != ApprovalStatus.CANCELLED) {
                        oldRecord.cancel("差旅申请修改，撤销旧审批并重新提交");
                        Assert.isTrue(approvalRecordDomainService.updateById(oldRecord), () -> SystemIntervalException.of("撤销旧审批失败"));
                        oldApprovalCancelled = true;
                    }
                }

                // 重置为草稿并保存字段变更
                order.setStatus(TravelOrderStatus.DRAFT);
                order.setApprovalId(null);
                Assert.isTrue(travelOrderDomainService.updateById(order), () -> SystemIntervalException.of("差旅单字段更新失败"));
                if (itineraryChanged) {
                    // 与订单修改同事务删除当前关联；回滚时保留原方案。
                    itineraryPlanMapper.deleteById(order.getOrderId());
                }

                // 发起新的审批
                ApprovalRecord newRecord = approvalRecordDomainService.submit(order);
                Assert.notNull(newRecord, () -> SystemIntervalException.of("提交新审批失败"));
                order.submitted(newRecord.getProcessInstanceId());
                Assert.isTrue(travelOrderDomainService.updateById(order), () -> SystemIntervalException.of("差旅单关联新审批失败"));

                log.info("【修改差旅单】修改完成，orderId:{}, newApprovalId:{}", order.getOrderId(), newRecord.getProcessInstanceId());
                String message = oldApprovalCancelled
                        ? "差旅申请已修改，旧审批单已撤销，新审批单已重新提交，等待审批结果。"
                        : "差旅申请已修改，新审批单已提交，等待审批结果。";
                return new ModifyTravelApprovalResult(
                        order.getOrderId(), order.getStatus().getCode(), oldApprovalId, oldApprovalCancelled,
                        newRecord.getProcessInstanceId(), newRecord.getStatus().getCode(), String.join("、", updatedFields),
                        message, List.of(), null);
            } catch (Exception e) {
                log.error("【修改差旅单】事务执行失败，orderId:{}", request.getOrderId(), e);
                status.setRollbackOnly();
                return null;
            }
        });
        if (outcome == null) {
            return R.failed(ResultCode.SYSTEM_BUSY);
        }
        return R.success(outcome);
    }

    /**
     * 根据用户id获取差旅单信息
     * @param userId
     * @return
     */
    public R<List<TravelOrderView>> getUserOrders(String userId) {
        // 1.查询该用户的差旅单
        List<TravelOrder> travelOrders = travelOrderDomainService.findListByUserId(userId);
        if (CollectionUtils.isEmpty(travelOrders)) {
            return R.ok(List.of());
        }

        // 2. 批量查询差旅单关联的审批信息
        List<String> approvalIds = travelOrders.stream().map(TravelOrder::getApprovalId).filter(StringUtils::isNotBlank).toList();
        // 草稿可能没有审批单；避免对空 ID 列表执行批量查询。
        List<ApprovalRecord> records = approvalIds.isEmpty() ? List.of() : approvalRecordDomainService.listByIds(approvalIds);
        Map<String, ApprovalRecord> approvalMap = CollectionUtils.isEmpty(records) ? new HashMap<>() : records.stream().collect(Collectors.toMap(ApprovalRecord::getProcessInstanceId, Function.identity()));

        // 3. 查询用户预订记录
        List<BookingRecord> bookingRecords = bookingRecordDomainService.findListByUserIdAndOrderIds(userId, travelOrders.stream().map(TravelOrder::getOrderId).toList());
        Map<String, List<BookingRecord>> bookingMap = CollectionUtils.isEmpty(bookingRecords) ? new HashMap<>() : bookingRecords.stream().collect(Collectors.groupingBy(BookingRecord::getTravelOrderId));

        // 只读取已发布的单号，订单列表不加载大体积规划和审核快照。
        Set<String> publishedOrderIds = new HashSet<>(itineraryPlanMapper.findPublishedOrderIds(
                travelOrders.stream().map(TravelOrder::getOrderId).toList()));

        // 构造响应数据
        return R.ok(travelOrders.stream().map(order -> {
            ApprovalRecord record = approvalMap.get(order.getApprovalId());
            List<BookingRecord> bookingRecordList = bookingMap.get(order.getOrderId());
            TravelOrderView view = TravelOrderView.of(order, record, bookingRecordList);
            view.setHasPlan(publishedOrderIds.contains(order.getOrderId()));
            return view;
        }).toList());
    }

    private static List<String> updatedOrderFields(TravelOrderModifyRequest request, TravelOrder order) {
        List<String> updatedFields = new ArrayList<>();
        if (StringUtils.isNotBlank(request.getDestination())) {
            order.setDestination(request.getDestination());
            updatedFields.add("目的地");
        }
        if (StringUtils.isNotBlank(request.getDepartureCity())) {
            order.setDepartureCity(request.getDepartureCity());
            updatedFields.add("出发城市");
        }
        if (StringUtils.isNotBlank(request.getDepartureDate())) {
            order.setDepartureDate(request.getDepartureDate());
            updatedFields.add("出发日期");
        }
        if (StringUtils.isNotBlank(request.getReturnDate())) {
            order.setReturnDate(request.getReturnDate());
            updatedFields.add("返回日期");
        }
        if (StringUtils.isNotBlank(request.getPurpose())) {
            order.setPurpose(request.getPurpose());
            updatedFields.add("出差事由");
        }
        return updatedFields;
    }

    /**
     * 查库验证差旅单状态，确保写操作真正落库成功。
     * @param orderId
     * @param status
     * @return
     */
    public R<Void> verifyOrderStatus(String orderId, TravelOrderStatus status) {
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
                List.of(TravelOrderStatus.DRAFT, TravelOrderStatus.SUBMITTED, TravelOrderStatus.APPROVED),
                request.getDepartureDate(),
                request.getReturnDate());
        if (CollectionUtils.isEmpty(activeOrders)) {
            return null;
        }
        return activeOrders.stream().filter(o -> StringUtils.equals(o.getDepartureCity(), request.getDepartureCity())
                && StringUtils.equals(o.getDestination(), request.getDestination())
                && StringUtils.equals(o.getDepartureDate(), request.getDepartureDate())
                && StringUtils.equals(o.getReturnDate(), request.getReturnDate())).findFirst().orElse(null);
    }

    private SubmitTravelApprovalResult buildApprovalResult(TravelOrder order, ApprovalRecord record) {
        return new SubmitTravelApprovalResult(order.getOrderId(), order.getApprovalId(),
                order.getStatus().getCode(), record == null ? null : record.getStatus().getCode(),
                record == null ? null : DateUtil.format(record.getCreated(), DatePattern.NORM_DATE_PATTERN),
                order.getDestination(), order.getDepartureCity(), order.getDepartureDate(), order.getReturnDate(), order.getPurpose());
    }


}
