package com.fons.cloud.ai.trip.infrastructure.client.model.tuniu;

import com.alibaba.fastjson2.annotation.JSONField;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 途牛酒店详情及房型报价查询参数。Trip第一阶段固定查询一间房。
 *
 * @author hongqy
 */
public record TuniuHotelDetailRequest(
        /** 酒店ID，途牛详情接口要求数字类型。 */
        Long hotelId,
        /** 入住日期。 */
        @JSONField(format = "yyyy-MM-dd") LocalDate checkIn,
        /** 离店日期。 */
        @JSONField(format = "yyyy-MM-dd") LocalDate checkOut,
        /** 房间数，当前固定为1。 */
        Integer roomNum,
        /** 成人数。 */
        Integer adultNum,
        /** 儿童数。 */
        Integer childNum,
        /** 儿童年龄列表。 */
        List<Integer> childAges) {

    public TuniuHotelDetailRequest {
        if (childAges != null) {
            childAges = Collections.unmodifiableList(new ArrayList<>(childAges));
        }
    }
}
