package com.dtech.dhan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for Dhan Historical Data API
 * POST /v2/charts/historical
 * POST /v2/charts/intraday
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanHistoricalRequest {

    @JsonProperty("securityId")
    private String securityId;

    @JsonProperty("exchangeSegment")
    private String exchangeSegment;

    @JsonProperty("instrument")
    private String instrument;

    @JsonProperty("expiryCode")
    private Integer expiryCode;

    @JsonProperty("fromDate")
    private String fromDate; // Format: YYYY-MM-DD

    @JsonProperty("toDate")
    private String toDate; // Format: YYYY-MM-DD

    @JsonProperty("interval")
    private Integer interval; // For intraday: 1, 5, 15, 25, 60
}
