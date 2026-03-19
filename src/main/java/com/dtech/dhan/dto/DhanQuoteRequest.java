package com.dtech.dhan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for Dhan Market Quote API
 * POST /v2/marketfeed/quote
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanQuoteRequest {

    @JsonProperty("instruments")
    private List<InstrumentIdentifier> instruments;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InstrumentIdentifier {
        @JsonProperty("securityId")
        private String securityId;

        @JsonProperty("exchangeSegment")
        private String exchangeSegment;
    }
}
