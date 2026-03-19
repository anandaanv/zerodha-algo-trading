package com.dtech.dhan.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Response DTO for Dhan Historical Data API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DhanHistoricalResponse {

    @JsonProperty("data")
    private List<HistoricalCandle> data;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoricalCandle {
        @JsonProperty("timestamp")
        private String timestamp; // ISO 8601 format

        @JsonProperty("open")
        private Double open;

        @JsonProperty("high")
        private Double high;

        @JsonProperty("low")
        private Double low;

        @JsonProperty("close")
        private Double close;

        @JsonProperty("volume")
        private Long volume;

        @JsonProperty("open_interest")
        private Long openInterest;
    }
}
