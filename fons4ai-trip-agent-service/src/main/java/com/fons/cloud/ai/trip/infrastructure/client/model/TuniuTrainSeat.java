package com.fons.cloud.ai.trip.infrastructure.client.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.function.Function;

/**
 * 途牛席别字段与Trip席别名称的对应关系，仅在供应商适配层使用。
 * 不将卧铺、无座等报价降级成二等座。
 *
 * @author hongqy
 */
@Getter
@RequiredArgsConstructor
public enum TuniuTrainSeat {

    PREMIUM_SOFT_SLEEPER("高级软卧", TuniuTrainSearchResponse.Price::gjrwPrice, TuniuTrainSearchResponse.SeatAvailable::gjrwNum),
    SOFT_SLEEPER("软卧", TuniuTrainSearchResponse.Price::rwPrice, TuniuTrainSearchResponse.SeatAvailable::rwNum),
    SOFT_SEAT("软座", TuniuTrainSearchResponse.Price::rzPrice, TuniuTrainSearchResponse.SeatAvailable::rzNum),
    BUSINESS_SEAT("商务座", TuniuTrainSearchResponse.Price::swzPrice, TuniuTrainSearchResponse.SeatAvailable::swzNum),
    PREMIUM_SEAT("特等座", TuniuTrainSearchResponse.Price::tdzPrice, TuniuTrainSearchResponse.SeatAvailable::tdzNum),
    STANDING("无座", TuniuTrainSearchResponse.Price::wzPrice, TuniuTrainSearchResponse.SeatAvailable::wzNum),
    HARD_SLEEPER("硬卧", TuniuTrainSearchResponse.Price::ywPrice, TuniuTrainSearchResponse.SeatAvailable::ywNum),
    HARD_SEAT("硬座", TuniuTrainSearchResponse.Price::yzPrice, TuniuTrainSearchResponse.SeatAvailable::yzNum),
    SECOND_CLASS_SEAT("二等座", TuniuTrainSearchResponse.Price::edzPrice, TuniuTrainSearchResponse.SeatAvailable::edzNum),
    FIRST_CLASS_SEAT("一等座", TuniuTrainSearchResponse.Price::ydzPrice, TuniuTrainSearchResponse.SeatAvailable::ydzNum),
    EMU_SLEEPER("动卧", TuniuTrainSearchResponse.Price::dwPrice, TuniuTrainSearchResponse.SeatAvailable::dwNum),
    FIRST_CLASS_SLEEPER("一等卧", TuniuTrainSearchResponse.Price::ydwPrice, TuniuTrainSearchResponse.SeatAvailable::ydwNum),
    SECOND_CLASS_SLEEPER("二等卧", TuniuTrainSearchResponse.Price::edwPrice, TuniuTrainSearchResponse.SeatAvailable::edwNum);

    /**
     * 标准候选使用的席别名称。
     */
    private final String label;
    /**
     * 读取供应商对应票价字段。
     */
    private final Function<TuniuTrainSearchResponse.Price, String> priceReader;
    /**
     * 读取供应商对应余票字段。
     */
    private final Function<TuniuTrainSearchResponse.SeatAvailable, Integer> stockReader;
}
