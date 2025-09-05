package com.dtech.algo.controller.dto;

import com.dtech.algo.service.ASTASignalService;
import com.dtech.algo.series.Interval;
import com.dtech.algo.service.TimeframeType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Request for ASTA screening across multiple symbols and timeframes.
 * timeframeMap keys must be one of: RIPPLE, WAVE, TIDE, SUPER_TIDE
 * values must be Interval enum names (e.g., FifteenMinute, OneHour, Day, Week)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ASTAScreenRequest {

    @NotEmpty(message = "At least one symbol is required")
    private List<String> symbols;

    @NotNull(message = "timeframeMap is required")
    private Map<TimeframeType, Interval> timeframeMap;

    @Builder.Default
    private int candleCount = 300;
}
