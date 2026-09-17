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

    /**
     * 根据id和用户id查找
     * @param id     主键
     * @param userId 用户id
     * @return
     */
    ApprovalRecord findByIdAndUserId(String id, String userId);

    /**
     * 根据用户id查询最后的审批记录
     * @param userId 用户id
     * @return
     */
    ApprovalRecord findLatestByUserId(String userId);
}
