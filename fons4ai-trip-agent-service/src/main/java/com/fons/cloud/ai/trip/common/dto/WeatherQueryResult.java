package com.fons.cloud.ai.trip.common.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * 天气查询结果。日期、预报时段和实况观测时间均按供应商的目的地当地时间解释。
 * 数值字段未附加单位字符串，供应商未提供的可选数值为null，不用0代替缺失值。
 *
 * @param city 请求的城市名称，不代表供应商已精确匹配该行政区域
 * @param source 数据来源
 * @param queryDate 指定的预报日期；null表示获取全部可用预报
 * @param beyondRange 是否超出本次接口返回的预报日期范围，不表示服务故障
 * @param forecastStartDate 接口实际返回的预报起始日期
 * @param forecastEndDate 接口实际返回的预报结束日期
 * @param current 当前实况，与指定日期的预报分别表示；不可用或超出范围时为null
 * @param forecast 未指定日期时返回全部预报，指定日期时只返回该日；超出范围时为空
 * @author hongqy
 */
public record WeatherQueryResult(
        String city,
        String source,
        LocalDate queryDate,
        boolean beyondRange,
        LocalDate forecastStartDate,
        LocalDate forecastEndDate,
        CurrentWeather current,
        List<DailyForecast> forecast) {

    /**
     * 当前实况，不可当作未来日期的天气预报。
     *
     * @param tempC 温度，摄氏度
     * @param feelsLikeC 体感温度，摄氏度
     * @param humidity 相对湿度，百分比
     * @param windKmph 风速，千米/小时
     * @param description 天气描述
     * @param observationTime 供应商提供的当地观测时间原文，不附加业务时区
     */
    public record CurrentWeather(BigDecimal tempC, BigDecimal feelsLikeC, BigDecimal humidity,
                                 BigDecimal windKmph, String description, String observationTime) {
    }

    /**
     * 每日天气预报。
     *
     * @param date 预报日期
     * @param maxTempC 最高温度，摄氏度
     * @param minTempC 最低温度，摄氏度
     * @param hourly 供应商返回的日内预报时段，未提供时为空列表
     */
    public record DailyForecast(LocalDate date, BigDecimal maxTempC, BigDecimal minTempC,
                                List<HourlyForecast> hourly) {
    }

    /**
     * 日内天气预报，时间是目的地当地时间，不转换为服务器时区。
     *
     * @param time 预报时段，例如00:00、03:00
     * @param tempC 温度，摄氏度
     * @param windKmph 风速，千米/小时
     * @param chanceOfRain 降雨概率，百分比
     * @param description 天气描述
     */
    public record HourlyForecast(LocalTime time, BigDecimal tempC, BigDecimal windKmph,
                                 BigDecimal chanceOfRain, String description) {
    }
}
