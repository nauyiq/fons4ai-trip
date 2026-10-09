package com.fons.cloud.ai.trip.common.dto;

/** 两国间的签证快速判断；未知字段保持 null，不推断为免签。 */
public record VisaQuickCheckResult(
        String passport,
        String destination,
        String requirement,
        Integer visaFreeDays,
        Boolean visaRequired,
        String lastVerified) {
}
