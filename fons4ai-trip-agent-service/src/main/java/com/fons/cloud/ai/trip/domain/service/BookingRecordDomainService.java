package com.fons.cloud.ai.trip.domain.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.fons.cloud.ai.trip.common.constants.BookingType;
import com.fons.cloud.ai.trip.domain.entity.BookingRecord;

import java.util.List;

/**
 * @author hongqy
 */
public interface BookingRecordDomainService extends IService<BookingRecord> {

    /**
     * 根据用户ID和差旅单ID
     * @param userId        用户ID
     * @param travelOrderId 差旅单ID
     * @return
     */
    List<BookingRecord> findByUserIdAndOrderId(String userId, String travelOrderId);

    /**
     * 根据ID和用户id查找
     * @param bookingId 主键
     * @param userId    用户ID
     * @return
     */
    BookingRecord findByIdAndUser(String bookingId, String userId);

    /**
     * 根据用户id和差旅单ID查找， 可选指定类型
     * @param userId         用户id
     * @param travelOrderId  差旅单ID
     * @param bookingType   预订类型
     * @return
     */
    List<BookingRecord> findByUserIdAndTravelOrderId(String userId, String travelOrderId, BookingType bookingType);

    /**
     * 根据用户id和业务类型查找, 可选指定类型
     * @param userId 用户id
     * @param bookingType 业务类型, 非必选
     * @return
     */
    List<BookingRecord> findByUserIdAndBizType(String userId, BookingType bookingType);
}
