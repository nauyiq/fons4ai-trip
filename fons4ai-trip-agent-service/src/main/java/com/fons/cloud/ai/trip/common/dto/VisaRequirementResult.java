package com.fons.cloud.ai.trip.common.dto;

import java.util.List;
import java.util.Map;

/** 签证详细要求；套餐未解锁的扩展字段不会作为事实返回。 */
public record VisaRequirementResult(
        String passport,
        String destination,
        String language,
        String requirement,
        Integer visaFreeDays,
        Boolean visaRequired,
        String description,
        List<String> documentsRequired,
        List<String> process,
        List<String> tips,
        Integer passportValidityMonths,
        Boolean verified,
        Map<String, Object> extendedDetails) {
}
