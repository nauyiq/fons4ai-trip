package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.common.constants.OrderStatus;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;

import java.util.Collection;
import java.util.List;

/**
 * @author hongqy
 */
public interface TravelOrderDomainService extends IService<TravelOrder> {

    /**
     * 查询用户所有生效中的差旅单（DRAFT / SUBMITTED / APPROVED），
     * 可选地与给定日期范围 [fromDate, toDate] 有重叠。
     * <p>重叠判定：existing.departureDate <= toDate AND existing.returnDate >= fromDate。
     * <p>任何一端传 null 则不参与该端的比较。
     */
    List<TravelOrder> findActiveByUserIdAndDateRange(String userId, Collection<OrderStatus> statuses,
                                                     String fromDate, String toDate);

}
