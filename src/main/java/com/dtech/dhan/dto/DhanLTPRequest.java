package com.dtech.dhan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request DTO for Dhan LTP (Last Traded Price) API
 * POST /v2/marketfeed/ltp
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanLTPRequest {

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
