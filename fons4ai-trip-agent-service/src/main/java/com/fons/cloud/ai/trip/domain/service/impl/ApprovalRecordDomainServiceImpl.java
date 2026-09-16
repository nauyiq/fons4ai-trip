package com.fons.cloud.ai.trip.domain.service.impl;

import com.alibaba.fastjson2.JSON;
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
                .processInstanceId(IdGenerator.next("AP_"))
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
