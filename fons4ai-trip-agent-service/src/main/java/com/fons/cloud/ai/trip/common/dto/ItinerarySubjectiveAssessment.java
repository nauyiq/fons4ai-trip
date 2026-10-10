package com.fons.cloud.ai.trip.common.dto;

import java.util.List;

/**
 * 模型给出的待校验主观发现，不直接作为最终审核结论。
 *
 * @param findings 逐方案的风险或偏好偏差
 * @author hongqy
 */
public record ItinerarySubjectiveAssessment(List<ItinerarySubjectiveFinding> findings) {
}
