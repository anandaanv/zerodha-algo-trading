package com.dtech.kitecon.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessAnalysisRequest {

    @JsonProperty("raw_engine_output")
    private String rawEngineOutput;

    @JsonProperty("symbol")
    private String symbol;

    @JsonProperty("current_price")
    private double currentPrice;

    @JsonProperty("entry_timeframe")
    private String entryTimeframe;
}
