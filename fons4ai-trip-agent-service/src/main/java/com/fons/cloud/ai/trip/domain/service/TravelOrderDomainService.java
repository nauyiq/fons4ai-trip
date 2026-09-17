package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.common.constants.TravelOrderStatus;
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
     * 根据行程信息查询差旅单候选列表，包含各状态，不假定行程信息唯一。
     * @param userId         用户ID
     * @param departureCity  起飞城市
     * @param destination    目的地
     * @param departureDate  出发日期
     * @return 匹配的差旅单列表，无匹配时为空列表
     */
    List<TravelOrder> findByTravelDetailInfo(String userId, String departureCity, String destination, String departureDate);

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
    List<TravelOrder> findActiveByUserIdAndDateRange(String userId, Collection<TravelOrderStatus> statuses, String fromDate, String toDate);

    /**
     * 按状态和出发日期范围查询差旅单，日期边界包含当天。
     * 空状态集合不限制状态；任一日期边界为空时，不参与该端比较。
     * @param userId     用户ID
     * @param statuses  差旅单状态集合
     * @param startDate 出发日期下界，可选
     * @param endDate   出发日期上界，可选
     * @return
     */
    List<TravelOrder> findByStatusAndUserIdAndDateRange(String userId, Collection<TravelOrderStatus> statuses, String startDate, String endDate);

    /**
     * 取消订单以及审批记录
      * @param order   订单
      * @param reason  取消原因
     * @return
     */
    CancelOderOutcome cancelWithApproval(TravelOrder order, String reason);


}
