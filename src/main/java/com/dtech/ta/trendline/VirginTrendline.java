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
}
