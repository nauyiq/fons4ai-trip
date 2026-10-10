package com.fons.cloud.ai.trip.common.constants;

/**
 * 审核所需的可选信息未覆盖的原因。
 * 原因和审核维度由代码确定，展示文案不参与业务判断。
 *
 * @author hongqy
 */
public enum ItineraryReviewCoverageReason {

    PREFERENCE_MISSING(ItineraryReviewDimension.EXPERIENCE_AND_PREFERENCE),
    WEATHER_OUT_OF_RANGE(ItineraryReviewDimension.RESILIENCE),
    WEATHER_NO_FORECAST(ItineraryReviewDimension.RESILIENCE),
    WEATHER_QUERY_FAILED(ItineraryReviewDimension.RESILIENCE),
    NEWS_NOT_CONFIGURED(ItineraryReviewDimension.RESILIENCE),
    NEWS_NOT_FOUND(ItineraryReviewDimension.RESILIENCE),
    NEWS_METADATA_MISSING(ItineraryReviewDimension.RESILIENCE),
    NEWS_QUERY_FAILED(ItineraryReviewDimension.RESILIENCE);

    private final ItineraryReviewDimension dimension;

    ItineraryReviewCoverageReason(ItineraryReviewDimension dimension) {
        this.dimension = dimension;
    }

    public ItineraryReviewDimension dimension() {
        return dimension;
    }
}
