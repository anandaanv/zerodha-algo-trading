package com.dtech.dhan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for Dhan LTP API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanLTPResponse {

    @JsonProperty("data")
    private List<LTPData> data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LTPData {
        @JsonProperty("security_id")
        private String securityId;

        @JsonProperty("exchange_segment")
        private String exchangeSegment;

        @JsonProperty("last_price")
        private Double lastPrice;
    }
}
