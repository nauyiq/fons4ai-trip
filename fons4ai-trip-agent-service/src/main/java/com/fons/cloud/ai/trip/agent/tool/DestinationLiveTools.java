package com.fons.cloud.ai.trip.agent.tool;

import com.fons.cloud.ai.trip.common.constants.DestinationNewsTopic;
import com.fons.cloud.ai.trip.common.constants.TripAgentToolResultCode;
import com.fons.cloud.ai.trip.common.dto.DestinationNewsQueryResult;
import com.fons.cloud.ai.trip.common.dto.WeatherQueryResult;
import com.fons.cloud.ai.trip.infrastructure.client.DestinationNewsClient;
import com.fons.cloud.ai.trip.infrastructure.client.WeatherClient;
import com.fons.cloud.common.base.exception.BusinessRuntimeException;
import com.fons.cloud.common.result.R;
import com.fons.cloud.common.result.ResultCode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 出差目的地实时联网查询工具集：天气查询 + 目的地资讯查询。
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DestinationLiveTools implements BaseTool {
    public static final List<String> TOOLS = List.of("query_weather", "query_destination_news");

    private final WeatherClient weatherClient;
    private final DestinationNewsClient destinationNewsClient;

    /**
     * 查询实时天气及指定日期预报；预报范围以供应商实际返回的数据为准。
     */
    @Tool(name = "query_weather", description = "查询目的城市的当前天气及短期预报，仅支持今天及未来2天（共3天）。"
            + "已知日期超出此范围时不要调用，应使用已接入的长周期天气工具；没有可用工具时说明暂无法查询。"
            + "SUCCESS 表示查询已完成；data.beyondRange=true 表示日期超出 forecastStartDate/forecastEndDate，无法提供该日预报。"
            + "current 是当前天气，指定日期预报看 forecast，不可用当前天气代替未来预报。")
    public R<WeatherQueryResult> queryWeather(
            @ToolParam(name = "city", description = "目的城市，必填，支持中文或英文，如上海、Tokyo、Paris；多城市分别调用") String city,
            @ToolParam(name = "date", description = "预报日期，YYYY-MM-DD，仅限今天及未来2天；不传或空白时返回所有可用预报", required = false) String date) {
        String destinationCity = StringUtils.trimToEmpty(city);
        String queryDateText = StringUtils.trimToNull(date);
        log.info("[TOOL][query_weather] city={}, date={}", destinationCity, queryDateText);
        if (destinationCity.isEmpty()) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "city 不能为空，请提供目的城市");
        }

        LocalDate queryDate = null;
        if (queryDateText != null) {
            if (queryDateText.length() != 10) {
                return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "date 必须为 YYYY-MM-DD 格式的有效日期");
            }
            try {
                queryDate = LocalDate.parse(queryDateText);
            } catch (DateTimeParseException e) {
                return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "date 必须为 YYYY-MM-DD 格式的有效日期");
            }
        }

        try {
            WeatherQueryResult result = weatherClient.queryWeather(destinationCity, queryDate);
            String message = result.beyondRange() ? "指定日期超出可用预报范围，请勿重复调用；改用已接入的长周期天气工具，没有可用工具时说明该日天气暂不可查询。"
                    : "天气查询成功，请区分当前天气与指定日期预报。";
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, result);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][query_weather] 天气查询未完成，city={}, date={}, code={}", destinationCity, queryDateText, e.getCode());
            return R.failed(resolveBusinessErrorCode(e).getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][query_weather] 天气查询失败，city={}, date={}", destinationCity, queryDateText, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "天气查询失败，不能据此判断天气状况，请稍后重试。");
        }
    }

    /**
     * 工具层将业务主题转换成中文关键词，Client 负责执行查询。
     * 外层 SUCCESS 与资讯服务是否可用、是否有匹配结果分别表达。
     */
    @Tool(name = "query_destination_news", description = "按城市和主题查询目的地资讯，返回标题、摘要、原文链接及发布时间。"
            + "SUCCESS 表示查询已完成；data.available=false 表示服务未配置，news 为空表示没有可用资讯，均不代表没有出行风险。"
            + "结果为关键词匹配，使用前核对城市、发布时间及原文，不作为实时交通或航班状态。")
    public R<DestinationNewsQueryResult> queryDestinationNews(
            @ToolParam(name = "city", description = "目的城市，必填，如上海；多城市分别调用") String city,
            @ToolParam(name = "topic", description = "主题：traffic交通、event活动、safety安全、flight航班、hotel酒店、policy政策、general综合；"
                    + "忽略大小写及首尾空格，不传或空白时为general", required = false) String topic) {
        String destinationCity = StringUtils.trimToEmpty(city);
        DestinationNewsTopic newsTopic = DestinationNewsTopic.of(topic);
        log.info("[TOOL][query_destination_news] city={}, topic={}", destinationCity, newsTopic);
        if (destinationCity.isEmpty()) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "city 不能为空，请提供目的城市");
        }
        if (newsTopic == null) {
            return R.failed(TripAgentToolResultCode.INVALID_PARAM.getCode(), "topic 仅支持 traffic、event、safety、flight、hotel、policy、general");
        }

        String query = newsTopic == DestinationNewsTopic.GENERAL ? destinationCity
                : destinationCity + " " + newsTopic.getQueryKeyword();
        try {
            DestinationNewsQueryResult result = destinationNewsClient.queryNews(query);
            String message;
            if (!result.available()) {
                message = "目的地资讯服务未配置，暂无法提供资讯，不能据此判断没有出行风险。";
            } else if (result.news().isEmpty()) {
                message = "本次查询没有匹配资讯，不能据此判断没有出行风险。";
            } else {
                message = "目的地资讯查询成功，请核对城市、发布时间及原文后使用。";
            }
            return R.success(TripAgentToolResultCode.SUCCESS.getCode(), message, result);
        } catch (BusinessRuntimeException e) {
            log.warn("[TOOL][query_destination_news] 资讯查询未完成，city={}, topic={}, code={}", destinationCity, newsTopic, e.getCode());
            return R.failed(resolveBusinessErrorCode(e).getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[TOOL][query_destination_news] 资讯查询失败，city={}, topic={}", destinationCity, newsTopic, e);
            return R.failed(TripAgentToolResultCode.INTERNAL_ERROR.getCode(), "目的地资讯查询失败，不能据此判断没有出行风险，请稍后重试。");
        }
    }

    /** Client 参数错误转换为工具契约码，其他错误按内部异常处理。 */
    private TripAgentToolResultCode resolveBusinessErrorCode(BusinessRuntimeException e) {
        if (ResultCode.PARAMS_ERROR.getCode().equals(e.getCode())) {
            return TripAgentToolResultCode.INVALID_PARAM;
        }
        return TripAgentToolResultCode.INTERNAL_ERROR;
    }

    @Override
    public List<String> tools() {
        return TOOLS;
    }
}
