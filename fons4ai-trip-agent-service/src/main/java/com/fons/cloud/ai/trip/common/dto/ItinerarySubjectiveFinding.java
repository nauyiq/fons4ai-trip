package com.fons.cloud.ai.trip.common.dto;

import com.fons.cloud.ai.trip.common.constants.ItinerarySubjectiveFindingType;

import java.util.List;

/**
 * 模型返回的单条主观发现，必须经 Java 校验后才能进入审核结果。
 *
 * @author hongqy
 */
public record ItinerarySubjectiveFinding(String proposalId, ItinerarySubjectiveFindingType type,
                                         String message, List<String> evidenceIds) {
}
