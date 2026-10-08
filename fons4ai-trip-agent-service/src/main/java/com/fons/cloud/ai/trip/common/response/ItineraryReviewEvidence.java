package com.fons.cloud.ai.trip.common.response;

import com.fons.cloud.ai.trip.common.constants.ItineraryReviewEvidenceSource;

import java.time.OffsetDateTime;

/**
 * 单条审核证据。展示值不参与业务规则判断。
 *
 * @author hongqy
 */
public record ItineraryReviewEvidence(ItineraryReviewEvidenceSource source,
                                      String referenceId,
                                      String field,
                                      OffsetDateTime observedAt,
                                      String actualValue,
                                      String expectedValue) {
}
