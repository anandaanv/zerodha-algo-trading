package com.dtech.algo.controller.dto;

import com.dtech.algo.screener.enums.Verdict;
import com.dtech.algo.series.Interval;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.util.List;

/**
 * Request DTO for the chart analysis API
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChartAnalysisRequest {
    
    /**
     * The trading symbol for which to generate charts
     */
    @NotBlank(message = "Symbol is required")
    private String symbol;
    
    /**
     * List of timeframes to analyze
     */
    @NotEmpty(message = "At least one timeframe is required")
    private List<Interval> timeframes;
    
    /**
     * Number of candles to include in each chart
     */
    @Positive(message = "Candle count must be positive")
    @Builder.Default
    private int candleCount = 100;

    private Verdict verdict;

    private String prompt;

}