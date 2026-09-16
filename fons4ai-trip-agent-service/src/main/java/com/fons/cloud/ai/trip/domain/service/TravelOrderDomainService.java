package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.common.constants.OrderStatus;
import com.fons.cloud.ai.trip.common.dto.CancelOderOutcome;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;

import java.util.Collection;
import java.util.List;

/**
 * @author hongqy
 */
public interface TravelOrderDomainService extends IService<TravelOrder> {

    /**
     * 根据订单ID和用户ID查询差旅单
     * @param orderId 订单ID
     * @param userId  用户ID
     * @return
     */
    TravelOrder findByOrderIdAndUserId(String orderId, String userId);

    /**
     * 查询用户所有生效中的差旅单（DRAFT / SUBMITTED / APPROVED），
     * 可选地与给定日期范围 [fromDate, toDate] 有重叠。
     * <p>重叠判定：existing.departureDate <= toDate AND existing.returnDate >= fromDate。
     * <p>任何一端传 null 则不参与该端的比较。
     * @param userId    用户ID
     * @param statuses  差旅单状态集合
     * @param fromDate  开始日期
     * @param toDate    结束日期
     * @return
     */
    List<TravelOrder> findActiveByUserIdAndDateRange(String userId, Collection<OrderStatus> statuses, String fromDate, String toDate);

    /**
     * 取消订单以及审批记录
      * @param order   订单
      * @param reason  取消原因
     * @return
     */
    CancelOderOutcome cancelWithApproval(TravelOrder order, String reason);

}
