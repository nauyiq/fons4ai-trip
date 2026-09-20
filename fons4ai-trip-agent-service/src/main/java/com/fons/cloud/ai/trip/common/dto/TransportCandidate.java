package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.constants.BookingType;

import java.time.OffsetDateTime;

/**
 * Trip机票、火车的标准搜索候选，每条表示具体航班或车次的一种舱位/席别报价。
 * 火车多个席别拆分为多条候选；供应商未提供的可选字段为null，不虚构默认舱位、库存或耗时。
 * 未完整的搜索候选可供展示，但进入规划前必须校验时间、价格等必要数据。
 *
 * @param candidateId 客户端生成的稳定标识，同一供应商、行程及报价选项补搜时保持一致；
 *                    不仅使用航班号或车次号，价格变化本身不应改变同一报价选项的标识
 * @param source 供应商来源，必填
 * @param type 交通类型，必填，只允许FLIGHT或TRAIN
 * @param carrier 航空公司或铁路运营方，未知为null
 * @param code 航班号或车次号，必填
 * @param origin 实际出发城市；供应商未提供时可由搜索条件补齐，附近机场模式须确认实际城市
 * @param destination 实际到达城市；附近机场模式不能直接冒充用户指定的城市
 * @param departureLocation 出发机场或车站名称，未知为null
 * @param arrivalLocation 到达机场或车站名称，未知为null
 * @param departureTerminal 出发航站楼，仅机票适用，未知为null
 * @param arrivalTerminal 到达航站楼，仅机票适用，未知为null
 * @param departureTime 出发当地时间及UTC偏移，未知为null，不能附加服务器默认时区
 * @param arrivalTime 到达当地时间及UTC偏移，未知为null，跨日信息必须保留
 * @param transitMinutes 实际交通耗时，单位分钟，未知为null；不包含未提供的市内接驳时间
 * @param price 标准报价，必填；金额和税费未知时由其中的可空字段表达
 * @param cabinClass 舱位或席别名称，保留供应商原文；不将未识别的席别默认成二等座
 * @param remainingSeats 明确余票数量，未知为null，0表示无余票
 * @param availabilityDescription 非数字余票说明原文，未提供为null，不默认无限库存
 * @param direct 是否无需中转，未知为null；不等同于途中完全不停靠
 * @param serviceDescription 航班或车次类型原文，如直飞、经停或高铁，未知为null
 * @param craftType 机型，仅机票适用，未知为null
 * @param sharedCode 共享航班号，仅机票适用，未知为null
 * @param refundPolicy 退改政策原文，未知为null
 * @author hongqy
 */
public record TransportCandidate(String candidateId, CandidateSource source, BookingType type,
                                  String carrier, String code, String origin, String destination,
                                  String departureLocation, String arrivalLocation,
                                  String departureTerminal, String arrivalTerminal,
                                  OffsetDateTime departureTime, OffsetDateTime arrivalTime,
                                  Long transitMinutes, CandidatePrice price, String cabinClass,
                                  Integer remainingSeats, String availabilityDescription, Boolean direct,
                                  String serviceDescription, String craftType, String sharedCode,
                                  String refundPolicy) {
}
