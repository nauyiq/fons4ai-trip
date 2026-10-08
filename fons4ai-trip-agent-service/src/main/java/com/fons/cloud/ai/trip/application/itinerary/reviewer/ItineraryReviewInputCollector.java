package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.alibaba.fastjson2.JSON;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewEvidenceSource;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewCoverageReason;
import com.fons.cloud.ai.trip.common.dto.DestinationNewsQueryResult;
import com.fons.cloud.ai.trip.common.dto.DestinationNewsQueryResult.NewsArticle;
import com.fons.cloud.ai.trip.common.dto.ItineraryReviewContext;
import com.fons.cloud.ai.trip.common.dto.ItineraryReviewCoverageGap;
import com.fons.cloud.ai.trip.common.dto.WeatherQueryResult;
import com.fons.cloud.ai.trip.common.dto.WeatherQueryResult.DailyForecast;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.Proposal;
import com.fons.cloud.ai.trip.common.response.ItineraryPlanningResult.TripRequest;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewEvidence;
import com.fons.cloud.ai.trip.infrastructure.client.DestinationNewsClient;
import com.fons.cloud.ai.trip.infrastructure.client.WeatherClient;
import com.fons.cloud.common.base.exception.SystemIntervalException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 为审核收集方案、真实天气和资讯事实。外部服务不可用时只记录覆盖范围，
 * 不将查询失败解释为天气良好或没有交通风险。
 *
 * @author hongqy
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ItineraryReviewInputCollector {

    private static final int MAX_NEWS_PER_CITY = 3;

    private final WeatherClient weatherClient;
    private final DestinationNewsClient destinationNewsClient;

    public ItineraryReviewContext collect(ItineraryPlanningResult planningResult,
                                          ItineraryReviewContext baseContext) {
        // 1. 从已保存方案和真实用户偏好构建可引用的本地事实
        Map<String, ItineraryReviewEvidence> facts = new LinkedHashMap<>();
        List<ItineraryReviewCoverageGap> coverageGaps = new ArrayList<>();
        addPlanFacts(planningResult, facts);
        addPreference(planningResult, facts, coverageGaps);
        // 2. 按行程城市和日期收集短期天气、交通资讯，记录不可用或未覆盖的信息
        TripRequest request = planningResult.getUserRequest();
        if (request != null) {
            addWeather(request, baseContext, facts, coverageGaps);
            addNews(request, baseContext, facts, coverageGaps);
        }
        // 3. 固定本轮审核时间与可信差旅单，返回各维度共用的事实快照
        return new ItineraryReviewContext(baseContext.reviewedAt(), baseContext.businessDate(),
                baseContext.currentTravelOrder(), facts, List.copyOf(coverageGaps));
    }

    private void addPlanFacts(ItineraryPlanningResult planningResult,
                              Map<String, ItineraryReviewEvidence> facts) {
        if (planningResult.getProposals() == null) {
            return;
        }
        for (Proposal proposal : planningResult.getProposals()) {
            if (proposal == null || StringUtils.isBlank(proposal.proposalId())) {
                continue;
            }
            String id = "proposal:" + proposal.proposalId();
            facts.put(id, new ItineraryReviewEvidence(ItineraryReviewEvidenceSource.PLAN_RESULT,
                            proposal.proposalId(), "proposal", planningResult.getGeneratedAt(),
                            JSON.toJSONString(proposal), null));
        }
    }

    private void addPreference(ItineraryPlanningResult planningResult,
                               Map<String, ItineraryReviewEvidence> facts,
                               List<ItineraryReviewCoverageGap> coverageGaps) {
        if (StringUtils.isBlank(planningResult.getPreferences())) {
            coverageGaps.add(new ItineraryReviewCoverageGap(
                    ItineraryReviewCoverageReason.PREFERENCE_MISSING, null, null));
            return;
        }
        String id = "user:preferences";
        facts.put(id, new ItineraryReviewEvidence(ItineraryReviewEvidenceSource.USER_PREFERENCE,
                        planningResult.getPlanId(), "preferences", planningResult.getGeneratedAt(),
                        planningResult.getPreferences(), null));
    }

    private void addWeather(TripRequest request, ItineraryReviewContext context,
                            Map<String, ItineraryReviewEvidence> facts,
                            List<ItineraryReviewCoverageGap> coverageGaps) {
        Map<String, LocalDate> queries = new LinkedHashMap<>();
        putWeatherQuery(queries, request.origin(), request.departureDate());
        putWeatherQuery(queries, request.destination(), request.departureDate());
        putWeatherQuery(queries, request.destination(), request.returnDate());
        putWeatherQuery(queries, request.origin(), request.returnDate());
        for (Map.Entry<String, LocalDate> query : queries.entrySet()) {
            String city = query.getKey().substring(0, query.getKey().lastIndexOf(':'));
            LocalDate date = query.getValue();
            if (date.isBefore(context.businessDate()) || date.isAfter(context.businessDate().plusDays(2))) {
                coverageGaps.add(new ItineraryReviewCoverageGap(
                        ItineraryReviewCoverageReason.WEATHER_OUT_OF_RANGE, city, date));
                continue;
            }
            try {
                WeatherQueryResult result = weatherClient.queryWeather(city, date);
                if (result.beyondRange() || result.forecast() == null || result.forecast().isEmpty()) {
                    coverageGaps.add(new ItineraryReviewCoverageGap(
                            ItineraryReviewCoverageReason.WEATHER_NO_FORECAST, city, date));
                    continue;
                }
                for (DailyForecast forecast : result.forecast()) {
                    String id = "weather:" + city + ":" + forecast.date();
                    facts.put(id, new ItineraryReviewEvidence(ItineraryReviewEvidenceSource.WEATHER,
                                    city + ":" + forecast.date(), "forecast", context.reviewedAt(),
                                    JSON.toJSONString(forecast), null));
                }
            } catch (SystemIntervalException e) {
                log.warn("[ItineraryReviewInputCollector] 天气查询失败，city={}, date={}", city, date, e);
                coverageGaps.add(new ItineraryReviewCoverageGap(
                        ItineraryReviewCoverageReason.WEATHER_QUERY_FAILED, city, date));
            }
        }
    }

    private void putWeatherQuery(Map<String, LocalDate> queries, String city, LocalDate date) {
        if (StringUtils.isNotBlank(city) && date != null) {
            queries.put(city.trim() + ":" + date, date);
        }
    }

    private void addNews(TripRequest request, ItineraryReviewContext context,
                         Map<String, ItineraryReviewEvidence> facts,
                         List<ItineraryReviewCoverageGap> coverageGaps) {
        Set<String> cities = new LinkedHashSet<>();
        if (StringUtils.isNotBlank(request.origin())) {
            cities.add(request.origin().trim());
        }
        if (StringUtils.isNotBlank(request.destination())) {
            cities.add(request.destination().trim());
        }
        for (String city : cities) {
            try {
                DestinationNewsQueryResult result = destinationNewsClient.queryNews(city + " 交通");
                if (!result.available()) {
                    coverageGaps.add(new ItineraryReviewCoverageGap(
                            ItineraryReviewCoverageReason.NEWS_NOT_CONFIGURED, city, null));
                    continue;
                }
                if (result.news() == null || result.news().isEmpty()) {
                    coverageGaps.add(new ItineraryReviewCoverageGap(
                            ItineraryReviewCoverageReason.NEWS_NOT_FOUND, city, null));
                    continue;
                }
                int count = 0;
                for (NewsArticle article : result.news()) {
                    if (article == null || StringUtils.isAnyBlank(article.title(), article.link())
                            || article.publishedAt() == null) {
                        continue;
                    }
                    String id = "news:" + city + ":" + count++;
                    facts.put(id, new ItineraryReviewEvidence(ItineraryReviewEvidenceSource.DESTINATION_NEWS,
                                    article.link(), "trafficNews", null,
                                    article.title() + "；" + StringUtils.defaultString(article.description())
                                            + "；发布时间：" + article.publishedAt()
                                            + " " + StringUtils.defaultString(article.publishedTimeZone())
                                            + "；发布方：" + StringUtils.defaultString(article.sourceName()), null));
                    if (count >= MAX_NEWS_PER_CITY) {
                        break;
                    }
                }
                if (count == 0) {
                    coverageGaps.add(new ItineraryReviewCoverageGap(
                            ItineraryReviewCoverageReason.NEWS_METADATA_MISSING, city, null));
                }
            } catch (SystemIntervalException e) {
                log.warn("[ItineraryReviewInputCollector] 交通资讯查询失败，city={}", city, e);
                coverageGaps.add(new ItineraryReviewCoverageGap(
                        ItineraryReviewCoverageReason.NEWS_QUERY_FAILED, city, null));
            }
        }
    }
}
