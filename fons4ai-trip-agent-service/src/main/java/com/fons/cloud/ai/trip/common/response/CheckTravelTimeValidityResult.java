package com.fons.cloud.ai.trip.common.response;

import java.util.List;

/**
 * 行程规划前的时间校验结果，不代表差旅申请已审批通过。
 * @param valid 时间检查是否允许继续规划
 * @param today 本次检查使用的当前日期，格式YYYY-MM-DD
 * @param blockedOrders 因时间原因不可规划的差旅单，无拦截时为空列表
 * @author hongqy
 */
public record CheckTravelTimeValidityResult(
        boolean valid,
        String today,
        List<BlockedOrder> blockedOrders
) {

    /**
     * 因行程已开始或已结束而被拦截的差旅单摘要。
     */
    public record BlockedOrder(
            String orderId,
            String destination,
            String departureDate,
            String returnDate,
            String reason) {
    }
}
