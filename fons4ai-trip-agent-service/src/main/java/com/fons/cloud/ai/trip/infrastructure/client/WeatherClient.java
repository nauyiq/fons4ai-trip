package com.fons.cloud.ai.trip.infrastructure.client;

import cn.hutool.core.lang.Assert;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.fons.cloud.ai.trip.common.dto.WeatherQueryResult;
import com.fons.cloud.ai.trip.common.dto.WeatherQueryResult.CurrentWeather;
import com.fons.cloud.ai.trip.common.dto.WeatherQueryResult.DailyForecast;
import com.fons.cloud.ai.trip.common.dto.WeatherQueryResult.HourlyForecast;
import com.fons.cloud.ai.trip.infrastructure.client.model.wttr.WttrWeatherResponse;
import com.fons.cloud.ai.trip.infrastructure.client.model.wttr.WttrWeatherResponse.CurrentCondition;
import com.fons.cloud.ai.trip.infrastructure.client.model.wttr.WttrWeatherResponse.Description;
import com.fons.cloud.ai.trip.infrastructure.client.model.wttr.WttrWeatherResponse.WeatherDay;
import com.fons.cloud.ai.trip.infrastructure.client.model.wttr.WttrWeatherResponse.WeatherHour;
import com.fons.cloud.ai.trip.infrastructure.config.properties.WeatherClientProperties;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import com.fons.cloud.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;

/**
 * 天气HTTP客户端，封装wttr.in请求与协议转换，不依赖AgentScope或差旅领域服务。
 * 不包含工具注册、R封装、用户追问、备用供应商选择或熔断编排。
 *
 * @author hongqy
 */
@Slf4j
@Component
public class WeatherClient {

    private static final String SOURCE = "wttr.in";
    private static final int TIME_ENCODING_BASE = 100;

    private final RestClient restClient;

    /**
     * 创建独立客户端，保留Spring提供的Builder定制，不修改共享Builder。
     */
    public WeatherClient(RestClient.Builder builder, WeatherClientProperties properties) {
        Assert.notBlank(properties.getBaseUrl(), () -> SystemIntervalException.of("天气服务地址不能为空"));
        URI baseUri;
        try {
            baseUri = URI.create(properties.getBaseUrl().trim());
        } catch (IllegalArgumentException e) {
            throw SystemIntervalException.of("天气服务地址格式无效", e);
        }
        Assert.isTrue(("https".equalsIgnoreCase(baseUri.getScheme()) || "http".equalsIgnoreCase(baseUri.getScheme()))
                        && baseUri.getHost() != null && baseUri.getRawQuery() == null && baseUri.getRawFragment() == null,
                () -> SystemIntervalException.of("天气服务地址必须为合法的HTTP或HTTPS地址，不能包含查询参数或片段"));
        validateTimeout(properties.getConnectTimeout(), "连接");
        validateTimeout(properties.getReadTimeout(), "读取");
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());
        this.restClient = builder.clone().baseUrl(baseUri.toString()).requestFactory(requestFactory).build();
    }

    /**
     * 查询城市当前实况和预报；date为空返回全部可用预报，否则仅返回指定日期。
     * 日期按目的地当地日历解释，范围以供应商实际响应为准，不猜测当前日期或硬编码预报天数。
     * 超出范围返回beyondRange=true；缺失范围内预报及HTTP、解析失败均抛出系统异常。
     *
     * @param city 城市名称，支持中文或英文，首尾空格会去除
     * @param date 明确的预报日期，可为空；相对日期由上层结合业务时间上下文解析
     * @return 具体天气DTO，不包含供应商原始响应
     */
    public WeatherQueryResult queryWeather(String city, LocalDate date) {
        Assert.notBlank(city, () -> BusinessRuntimeException.of(ResultCode.PARAMS_ERROR.getCode(), "天气查询城市不能为空"));
        String normalizedCity = city.trim();
        log.info("[WeatherClient] 查询天气，city={}, date={}", normalizedCity, date);
        String body;
        try {
            body = restClient.get().uri("/{city}?format=j1", normalizedCity)
                    .accept(MediaType.APPLICATION_JSON).retrieve().body(String.class);
        } catch (RestClientException e) {
            throw SystemIntervalException.of("天气服务HTTP请求失败", e);
        }
        Assert.notBlank(body, () -> SystemIntervalException.of("天气服务返回空响应"));
        WttrWeatherResponse response;
        try {
            response = JSON.parseObject(body, WttrWeatherResponse.class);
        } catch (JSONException e) {
            throw SystemIntervalException.of("天气服务响应格式无效", e);
        }
        Assert.notNull(response, () -> SystemIntervalException.of("天气服务返回空数据"));
        WttrWeatherResponse payload = response.data() == null ? response : response.data();
        Assert.notEmpty(payload.weather(), () -> SystemIntervalException.of("天气服务未返回预报数据"));

        List<DailyForecast> forecasts = payload.weather().stream()
                .map(this::toDailyForecast).sorted(Comparator.comparing(DailyForecast::date)).toList();
        LocalDate startDate = forecasts.getFirst().date();
        LocalDate endDate = forecasts.getLast().date();
        boolean beyondRange = date != null && (date.isBefore(startDate) || date.isAfter(endDate));
        if (beyondRange) {
            return new WeatherQueryResult(normalizedCity, SOURCE, date, true, startDate, endDate, null, List.of());
        }
        List<DailyForecast> selected = date == null ? forecasts
                : forecasts.stream().filter(forecast -> date.equals(forecast.date())).toList();
        Assert.notEmpty(selected, () -> SystemIntervalException.of("天气服务缺失指定日期的预报数据"));
        return new WeatherQueryResult(normalizedCity, SOURCE, date, false, startDate, endDate,
                toCurrentWeather(payload.currentCondition()), selected);
    }

    private CurrentWeather toCurrentWeather(List<CurrentCondition> conditions) {
        if (CollectionUtils.isEmpty(conditions)) {
            return null;
        }
        CurrentCondition current = conditions.getFirst();
        Assert.notNull(current, () -> SystemIntervalException.of("天气实况数据格式无效"));
        return new CurrentWeather(current.tempC(), current.feelsLikeC(), current.humidity(),
                current.windSpeedKmph(), getDescription(current.weatherDesc()), current.localObservationTime());
    }

    private DailyForecast toDailyForecast(WeatherDay day) {
        Assert.isTrue(day != null && day.date() != null,
                () -> SystemIntervalException.of("天气预报缺失日期"));
        List<HourlyForecast> hourly = CollectionUtils.isEmpty(day.hourly()) ? List.of()
                : day.hourly().stream().map(this::toHourlyForecast).sorted(Comparator.comparing(HourlyForecast::time)).toList();
        Assert.isTrue(day.maxTempC() != null || day.minTempC() != null || !hourly.isEmpty(),
                () -> SystemIntervalException.of("天气预报未包含可用天气数据"));
        return new DailyForecast(day.date(), day.maxTempC(), day.minTempC(), hourly);
    }

    private HourlyForecast toHourlyForecast(WeatherHour hour) {
        Assert.notNull(hour, () -> SystemIntervalException.of("天气时段数据格式无效"));
        return new HourlyForecast(parseHourlyTime(hour.time()), hour.tempC(), hour.windSpeedKmph(),
                hour.chanceOfRain(), getDescription(hour.weatherDesc()));
    }

    /**
     * 将供应商HHmm整数编码转换为LocalTime，不能把无效时间默认为午夜。
     */
    private LocalTime parseHourlyTime(String time) {
        Assert.notBlank(time, () -> SystemIntervalException.of("天气预报时段缺失时间"));
        try {
            int value = Integer.parseInt(time.trim());
            return LocalTime.of(value / TIME_ENCODING_BASE, value % TIME_ENCODING_BASE);
        } catch (NumberFormatException | DateTimeException e) {
            throw SystemIntervalException.of("天气预报时段时间格式无效", e);
        }
    }

    private String getDescription(List<Description> descriptions) {
        return CollectionUtils.isEmpty(descriptions) || descriptions.getFirst() == null
                ? null : descriptions.getFirst().value();
    }

    private void validateTimeout(Duration timeout, String label) {
        Assert.isTrue(timeout != null && !timeout.isNegative() && !timeout.isZero(),
                () -> SystemIntervalException.of("天气服务" + label + "超时必须大于0"));
    }
}
