package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.ApprovalStatus;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
import com.fons.cloud.ai.trip.common.dto.CancelOderOutcome;
import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.mapper.ApprovalRecordMapper;
import com.fons.cloud.ai.trip.domain.mapper.TravelOrderMapper;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;

/**
 * @author hongqy
 */
@Service
@RequiredArgsConstructor
public class TravelOrderDomainServiceImpl extends ServiceImpl<TravelOrderMapper, TravelOrder> implements TravelOrderDomainService {
    private final ApprovalRecordMapper approvalRecordMapper;

    @Override
    public TravelOrder findByOrderIdAndUserId(String orderId, String userId) {
        return getOne(Wrappers.lambdaQuery(TravelOrder.class)
                .eq(TravelOrder::getOrderId, orderId)
                .eq(TravelOrder::getUserId, userId));
    }

    @Override
    public List<TravelOrder> findByTravelDetailInfo(String userId, String departureCity, String destination, String departureDate) {
        return list(Wrappers.lambdaQuery(TravelOrder.class)
                .eq(TravelOrder::getUserId, userId)
                .eq(TravelOrder::getDepartureCity, departureCity)
                .eq(TravelOrder::getDestination, destination)
                .eq(TravelOrder::getDepartureDate, departureDate)
                .orderByDesc(TravelOrder::getCreated)
                .orderByAsc(TravelOrder::getOrderId));
    }

    @Override
    public List<TravelOrder> findActiveByUserIdAndDateRange(String userId, Collection<TravelOrderStatus> statuses, String fromDate, String toDate) {
        LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                .eq(TravelOrder::getUserId, userId);
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in(TravelOrder::getStatus, statuses);
        } else {
            // 默认查询生效中状态
            wrapper.in(TravelOrder::getStatus,
                    TravelOrderStatus.DRAFT, TravelOrderStatus.SUBMITTED, TravelOrderStatus.APPROVED);
        }
        // 日期重叠：existing.dep <= toDate AND existing.ret >= fromDate
        if (StringUtils.isNotBlank(fromDate)) {
            wrapper.ge(TravelOrder::getReturnDate, fromDate);
        }
        if (StringUtils.isNotBlank(toDate)) {
            wrapper.le(TravelOrder::getDepartureDate, toDate);
        }
        wrapper.orderByAsc(TravelOrder::getDepartureDate);
        return list(wrapper);
    }

    @Override
    public List<TravelOrder> findByStatusAndUserIdAndDateRange(String userId, Collection<TravelOrderStatus> statuses, String startDate, String endDate) {
        LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                .eq(TravelOrder::getUserId, userId);
        if (CollectionUtils.isNotEmpty(statuses)) {
            wrapper.in(TravelOrder::getStatus, statuses);
        }

        if (StringUtils.isNotBlank(startDate)) {
            wrapper.ge(TravelOrder::getDepartureDate, startDate);
        }

        if (StringUtils.isNotBlank(endDate)) {
            wrapper.le(TravelOrder::getDepartureDate, endDate);
        }

        wrapper.orderByAsc(TravelOrder::getDepartureDate);
        return list(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CancelOderOutcome cancelWithApproval(TravelOrder order, String reason) {
        order.cancel();
        if (!this.updateById(order)) {
            // 取消订单失败直接返回结果
            return new CancelOderOutcome(false, false, null);
        }

        // 获取审批记录
        boolean approvalCancelled = false;
        String cancelledApprovalId = null;
        ApprovalRecord record = null;
        if (StringUtils.isNotBlank(order.getApprovalId())) {
            // 根据审批记录ID查询审批记录
            record = approvalRecordMapper.selectById(order.getApprovalId());
        } else {
            // 根据订单查询审批记录
            LambdaQueryWrapper<ApprovalRecord> wrapper = Wrappers.lambdaQuery(ApprovalRecord.class)
                    .eq(ApprovalRecord::getOrderId, order.getOrderId())
                    .orderByDesc(ApprovalRecord::getCreated)
                    .last("Limit 1");
            record = approvalRecordMapper.selectOne(wrapper);
        }

        if (record != null && record.getStatus() != ApprovalStatus.CANCELLED) {
            record.cancel(reason);
            if (approvalRecordMapper.updateById(record) <= 0) {
                throw SystemIntervalException.of("撤销关联审批失败");
            }
            approvalCancelled = true;
            cancelledApprovalId = record.getProcessInstanceId();
        }

        return new CancelOderOutcome(true, approvalCancelled, cancelledApprovalId);
    }


}
