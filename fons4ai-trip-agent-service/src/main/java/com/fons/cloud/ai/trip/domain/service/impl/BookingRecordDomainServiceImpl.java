package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.domain.entity.BookingRecord;
import com.fons.cloud.ai.trip.domain.mapper.BookingRecordMapper;
import com.fons.cloud.ai.trip.domain.service.BookingRecordDomainService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @author hongqy
 */
@Service
public class BookingRecordDomainServiceImpl extends ServiceImpl<BookingRecordMapper, BookingRecord> implements BookingRecordDomainService {

    @Override
    public List<BookingRecord> findByUserIdAndOrderId(String userId, String travelOrderId) {
        return list(Wrappers.lambdaQuery(BookingRecord.class)
                .eq(BookingRecord::getUserId, userId)
                .eq(BookingRecord::getTravelOrderId, travelOrderId)
                .orderByDesc(BookingRecord::getBookedAt));
    }

    @Override
    public BookingRecord findByIdAndUser(String bookingId, String userId) {
        return getOne(Wrappers.lambdaQuery(BookingRecord.class)
                .eq(BookingRecord::getBookingId, bookingId)
                .eq(BookingRecord::getUserId, userId));
    }

    @Override
    public List<BookingRecord> findByUserIdAndTravelOrderId(String userId, String travelOrderId, BookingType bookingType) {
        LambdaQueryWrapper<BookingRecord> wrapper = Wrappers.lambdaQuery(BookingRecord.class)
                .eq(BookingRecord::getUserId, userId)
                .eq(BookingRecord::getTravelOrderId, travelOrderId);

        if (bookingType != null) {
            wrapper.eq(BookingRecord::getBizType, bookingType);
        }

        wrapper.orderByDesc(BookingRecord::getBookedAt);
        return list(wrapper);
    }

    @Override
    public List<BookingRecord> findByUserIdAndBizType(String userId, BookingType bookingType) {
        LambdaQueryWrapper<BookingRecord> wrapper = Wrappers.lambdaQuery(BookingRecord.class)
                .eq(BookingRecord::getUserId, userId);
        if (bookingType != null) {
            wrapper.eq(BookingRecord::getBizType, bookingType);
        }
        wrapper.orderByDesc(BookingRecord::getBookedAt);
        return list(wrapper);
    }
}
