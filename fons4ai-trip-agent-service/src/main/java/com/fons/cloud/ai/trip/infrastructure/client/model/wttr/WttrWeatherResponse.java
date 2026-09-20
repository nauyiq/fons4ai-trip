package com.fons.cloud.ai.trip.infrastructure.client.model.wttr;

import com.alibaba.fastjson2.annotation.JSONField;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * wttr.in的j1协议对象，仅用于基础设施层反序列化，不直接作为Agent工具结果。
 * 支持根对象直接返回数据及data包装两种格式。
 *
 * @param currentCondition 当前实况
 * @param weather          每日预报
 * @param data             可选的协议包装对象
 * @author hongqy
 */
public record WttrWeatherResponse(
        @JSONField(name = "current_condition") List<CurrentCondition> currentCondition,
        List<WeatherDay> weather,
        WttrWeatherResponse data) {

    /**
     * @param tempC                摄氏温度
     * @param feelsLikeC           摄氏体感温度
     * @param humidity             湿度百分比
     * @param windSpeedKmph        风速，千米/小时
     * @param weatherDesc          天气描述列表
     * @param localObservationTime 目的地当地观测时间原文
     */
    public record CurrentCondition(
            @JSONField(name = "temp_C") BigDecimal tempC,
            @JSONField(name = "FeelsLikeC") BigDecimal feelsLikeC,
            BigDecimal humidity,
            @JSONField(name = "windspeedKmph") BigDecimal windSpeedKmph,
            List<Description> weatherDesc,
            @JSONField(name = "localObsDateTime") String localObservationTime) {
    }

    /**
     * @param date     预报日期
     * @param maxTempC 摄氏最高温度
     * @param minTempC 摄氏最低温度
     * @param hourly   日内预报时段
     */
    public record WeatherDay(LocalDate date,
                             @JSONField(name = "maxtempC") BigDecimal maxTempC,
                             @JSONField(name = "mintempC") BigDecimal minTempC,
                             List<WeatherHour> hourly) {
    }

    /**
     * @param time          供应商时间编码，例如0、300、2100
     * @param tempC         摄氏温度
     * @param windSpeedKmph 风速，千米/小时
     * @param chanceOfRain  降雨概率百分比
     * @param weatherDesc   天气描述列表
     */
    public record WeatherHour(String time,
                              BigDecimal tempC,
                              @JSONField(name = "windspeedKmph") BigDecimal windSpeedKmph,
                              @JSONField(name = "chanceofrain") BigDecimal chanceOfRain,
                              List<Description> weatherDesc) {
    }

    /**
     * @param value 供应商天气描述原文
     */
    public record Description(String value) {
    }
}
