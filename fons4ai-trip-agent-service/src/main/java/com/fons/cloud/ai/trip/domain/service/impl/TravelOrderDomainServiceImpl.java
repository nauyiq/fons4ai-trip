package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.TravelOrder;
import com.fons.cloud.ai.trip.domain.mapper.TravelOrderMapper;
import com.fons.cloud.ai.trip.domain.service.TravelOrderDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class TravelOrderDomainServiceImpl extends ServiceImpl<TravelOrderMapper, TravelOrder> implements TravelOrderDomainService {
}
