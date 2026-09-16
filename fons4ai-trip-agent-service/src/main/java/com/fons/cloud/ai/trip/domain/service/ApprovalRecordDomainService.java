package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.domain.entity.ApprovalRecord;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;

/**
 * @author hongqy
 */
public interface ApprovalRecordDomainService extends IService<ApprovalRecord> {

    /**
     * 差旅单提交审批
     * @param order
     * @return
     */
    ApprovalRecord submit(TravelOrder order);
}
