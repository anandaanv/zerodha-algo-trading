package com.dtech.ta.trendline;

import com.dtech.ta.elliott.EnrichedPivot;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class VirginTrendline {
    String label;                        // e.g. "Origin→W2", "W2→W4"
    EnrichedPivot anchor1;
    EnrichedPivot anchor2;
    double slope;
    double intercept;
    boolean support;                     // true = support trendline
    double priceAtCurrentBar;
    double distancePctFromCurrentPrice;

    /** Aggregated score from all matching scenarios. Higher = more significant. */
    @lombok.Builder.Default
    private int score = 0;

    /** Which scenarios contributed to this trendline's detection/scoring. */
    @lombok.Builder.Default
    private java.util.List<String> scenarioTags = new java.util.ArrayList<>();
}
