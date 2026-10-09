package com.fons.cloud.ai.trip.infrastructure.client.model.orizn;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Orizn 快速查询响应；忽略仅用于供应商升级推广的扩展字段。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OriznVisaCheckResponse(
        String passport,
        String destination,
        String requirement,
        @JsonProperty("visa_free_days") Integer visaFreeDays,
        @JsonProperty("visa_required") Boolean visaRequired,
        @JsonProperty("last_verified") String lastVerified) {
}
