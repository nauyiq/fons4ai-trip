package com.fons.cloud.ai.trip.application.itinerary.reviewer;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewSeverity;
import com.fons.cloud.ai.trip.common.constants.ItineraryReviewVerdict;
import com.fons.cloud.ai.trip.common.response.ItineraryReviewResult.ReviewIssue;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 行程审核器共享的无状态基础判断。
 *
 * @author hongqy
 */
final class ItineraryReviewSupport {

    private static final List<String> CITY_SUFFIXES = List.of("特别行政区", "市");

    private ItineraryReviewSupport() {
    }

    static boolean sameCity(String left, String right) {
        return StringUtils.equalsIgnoreCase(normalizeCity(left), normalizeCity(right));
    }

    static ItineraryReviewVerdict verdictOf(List<ReviewIssue> issues) {
        if (issues.stream().anyMatch(issue -> issue.severity() == ItineraryReviewSeverity.BLOCKING)) {
            return ItineraryReviewVerdict.BLOCKED;
        }
        return issues.isEmpty() ? ItineraryReviewVerdict.PASS : ItineraryReviewVerdict.WARNING;
    }

    private static String normalizeCity(String city) {
        String normalized = StringUtils.deleteWhitespace(StringUtils.trimToEmpty(city));
        for (String suffix : CITY_SUFFIXES) {
            if (normalized.endsWith(suffix)) {
                return StringUtils.removeEnd(normalized, suffix);
            }
        }
        return normalized;
    }
}
