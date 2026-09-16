package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.OrderStatus;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.mapper.TravelOrderMapper;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * @author hongqy
 */
@Service
public class TravelOrderDomainServiceImpl extends ServiceImpl<TravelOrderMapper, TravelOrder> implements TravelOrderDomainService {

    @Override
    public List<TravelOrder> findActiveByUserIdAndDateRange(String userId, Collection<OrderStatus> statuses, String fromDate, String toDate) {
        LambdaQueryWrapper<TravelOrder> wrapper = new LambdaQueryWrapper<TravelOrder>()
                .eq(TravelOrder::getUserId, userId);
        if (statuses != null && !statuses.isEmpty()) {
            wrapper.in(TravelOrder::getStatus, statuses);
        } else {
            // 默认查询生效中状态
            wrapper.in(TravelOrder::getStatus,
                    OrderStatus.DRAFT, OrderStatus.SUBMITTED, OrderStatus.APPROVED);
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
}
