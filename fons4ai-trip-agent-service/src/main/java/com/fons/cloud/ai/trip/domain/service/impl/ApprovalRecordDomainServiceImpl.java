package com.fons.cloud.ai.trip.domain.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.ApprovalStatus;
import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.mapper.ApprovalRecordMapper;
import com.fons.cloud.ai.trip.domain.service.ApprovalRecordDomainService;
import com.fons.cloud.ai.trip.infrastructure.util.IdGenerator;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * @author hongqy
 */
@Service
public class ApprovalRecordDomainServiceImpl extends ServiceImpl<ApprovalRecordMapper, ApprovalRecord> implements ApprovalRecordDomainService {

    @Override
    public ApprovalRecord submit(TravelOrder order) {
        ApprovalRecord record = ApprovalRecord.builder()
                .processInstanceId(IdGenerator.next(IdGenerator.Prefix.APPROVAL))
                .userId(order.getUserId())
                .orderId(order.getOrderId())
                .title(order.getPurpose())
                .status(ApprovalStatus.PENDING)
                .remark(null)
                .approvalForm(buildApprovalForm(order))
                .build();
        if (!this.save(record)) {
            return null;
        }
        return record;
    }

    @Override
    public ApprovalRecord findByIdAndUserId(String id, String userId) {
        return getOne(Wrappers.lambdaQuery(ApprovalRecord.class).eq(ApprovalRecord::getProcessInstanceId, id).eq(ApprovalRecord::getUserId, userId));
    }

    @Override
    public ApprovalRecord findLatestByUserId(String userId) {
        LambdaQueryWrapper<ApprovalRecord> wrapper = Wrappers.lambdaQuery(ApprovalRecord.class)
                .eq(ApprovalRecord::getUserId, userId)
                .orderByDesc(ApprovalRecord::getCreated)
                .last("Limit 1");
        return getOne(wrapper);
    }

    private static String buildApprovalForm(TravelOrder order) {
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("orderId", order.getOrderId());
        form.put("userId", order.getUserId());
        form.put("purpose", order.getPurpose());
        form.put("destination", order.getDestination());
        form.put("departureCity", order.getDepartureCity());
        form.put("departureDate", order.getDepartureDate());
        form.put("returnDate", order.getReturnDate());
        return JSON.toJSONString(form);
    }

}
