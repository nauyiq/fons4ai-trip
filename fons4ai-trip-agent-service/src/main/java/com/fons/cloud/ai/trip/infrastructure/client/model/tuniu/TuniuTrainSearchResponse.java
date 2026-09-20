package com.fons.cloud.ai.trip.infrastructure.client.model.tuniu;

import java.util.List;

/**
 * 途牛火车票搜索响应，票价空字符串及余票null按原协议保留，不转换成零价格或零库存。
 *
 * @author hongqy
 */
public record TuniuTrainSearchResponse(Boolean successCode, String queryId,
                                       Integer totalPageNum, List<Train> data) {

    /** 车次、站点及各席别的价格和余票。 */
    public record Train(String trainNum, String departStationName, String destStationName,
                        String trainType, String departureTime, String arrivalTime,
                        String duration, Price price, SeatAvailable seatAvailable) {
    }

    /** 单位元；空字符串表示该席别未提供价格。 */
    public record Price(String gjrwPrice, String rwPrice, String rzPrice, String swzPrice,
                        String tdzPrice, String wzPrice, String ywPrice, String yzPrice,
                        String edzPrice, String ydzPrice, String dwPrice,
                        String ydwPrice, String edwPrice) {
    }

    /** 各席别剩余数量；null表示未提供，0表示没有余票。 */
    public record SeatAvailable(Integer gjrwNum, Integer rwNum, Integer rzNum, Integer swzNum,
                                Integer tdzNum, Integer wzNum, Integer ywNum, Integer yzNum,
                                Integer edzNum, Integer ydzNum, Integer dwNum,
                                Integer ydwNum, Integer edwNum) {
    }
}
