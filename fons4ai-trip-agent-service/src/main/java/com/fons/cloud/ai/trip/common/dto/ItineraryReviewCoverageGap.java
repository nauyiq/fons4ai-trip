package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewCoverageReason;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewDimension;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import org.apache.commons.lang3.StringUtils;

import java.time.LocalDate;

/**
 * 单项审核信息覆盖缺口。原因用于判断，中文说明只用于模型输入和报告展示。
 *
 * @param reason 缺口原因
 * @param city 涉及城市；无城市时为null
 * @param date 涉及日期；无日期时为null
 * @author hongqy
 */
public record ItineraryReviewCoverageGap(ItineraryReviewCoverageReason reason, String city, LocalDate date) {

    public ItineraryReviewCoverageGap {
        if (reason == null) {
            throw SystemIntervalException.of("审核信息覆盖缺口原因不能为空");
        }
        if (reason != ItineraryReviewCoverageReason.PREFERENCE_MISSING && StringUtils.isBlank(city)) {
            throw SystemIntervalException.of("审核信息覆盖缺口城市不能为空");
        }
        if ((reason == ItineraryReviewCoverageReason.WEATHER_OUT_OF_RANGE
                || reason == ItineraryReviewCoverageReason.WEATHER_NO_FORECAST
                || reason == ItineraryReviewCoverageReason.WEATHER_QUERY_FAILED) && date == null) {
            throw SystemIntervalException.of("天气信息覆盖缺口日期不能为空");
        }
    }

    public ItineraryReviewDimension dimension() {
        return reason.dimension();
    }

    public String message() {
        return switch (reason) {
            case PREFERENCE_MISSING -> "没有真实用户偏好，本次不评价偏好匹配";
            case WEATHER_OUT_OF_RANGE -> city + " " + date + "超出短期天气预报范围";
            case WEATHER_NO_FORECAST -> city + " " + date + "没有可用天气预报";
            case WEATHER_QUERY_FAILED -> city + " " + date + "天气预报查询失败";
            case NEWS_NOT_CONFIGURED -> city + "交通资讯服务未配置";
            case NEWS_NOT_FOUND -> city + "没有检索到可核实的交通资讯，不代表没有风险";
            case NEWS_METADATA_MISSING -> city + "资讯缺少可核实的发布时间或来源";
            case NEWS_QUERY_FAILED -> city + "交通资讯查询失败";
        };
    }
}
