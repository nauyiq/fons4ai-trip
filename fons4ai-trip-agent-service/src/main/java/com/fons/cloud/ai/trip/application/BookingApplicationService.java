package com.fons.cloud.ai.trip.application;

import com.fons.cloud.ai.trip.common.constants.BookingStatus;
import com.fons.cloud.ai.trip.common.dto.BookingSummary;
import com.fons.cloud.ai.trip.domain.entity.BookingRecord;
import com.fons.cloud.ai.trip.domain.service.BookingRecordDomainService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 预定业务的应用层服务
 * @author hongqy
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BookingApplicationService {
    private final BookingRecordDomainService bookingRecordDomainService;

    /**
     * 查询行程关联的有效预订记录（排除已取消/已退款）
     * @param userId        用户ID
     * @param travelOrderId 差旅单ID
     * @return
     */
    public List<BookingSummary> queryAssociatedBookings(String userId, String travelOrderId) {
        List<BookingRecord> records = bookingRecordDomainService.findByUserIdAndOrderId(userId, travelOrderId);
        if (CollectionUtils.isEmpty(records)) {
            return List.of();
        }
        return records.stream()
                .filter(record -> record.getStatus() != BookingStatus.CANCELLED && record.getStatus() != BookingStatus.REFUNDED)
                .map(record -> new BookingSummary(
                        record.getBookingId(),
                        record.getBizType() == null ? null : record.getBizType().getCode(),
                        record.getTitle(),
                        record.getStatus() == null ?  null : record.getStatus().getCode(),
                        record.getExternalOrderNo())).toList();
    }


}
