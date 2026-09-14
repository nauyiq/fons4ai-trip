package com.fons.cloud.ai.trip.domain.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fons.cloud.ai.trip.domain.entity.BookingRecord;
import com.fons.cloud.ai.trip.domain.mapper.BookingRecordMapper;
import com.fons.cloud.ai.trip.domain.service.BookingRecordDomainService;
import org.springframework.stereotype.Service;

/**
 * @author hongqy
 */
@Service
public class BookingRecordDomainServiceImpl extends ServiceImpl<BookingRecordMapper, BookingRecord> implements BookingRecordDomainService {
}
