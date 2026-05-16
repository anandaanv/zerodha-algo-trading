package com.dtech.aitrader.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

/**
 * A generic pattern signal fired by a pattern detection engine (DTB, HNS, TLB, IMPULSE, etc.)
 * encapsulating all context needed for the PatternAgent to make a trade decision.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PatternSignal {
    private String symbol;                      // "WIPRO"
    private String patternType;                 // "DOUBLE_TOP", "HNS", "TLB_RETEST", "IMPULSE_RETEST"
    private String patternSource;               // "DTB", "HNS", "TLB", "IMPULSE"
    private String signalRef;                   // free-form, e.g. "dtb-sim-run-22-trade-3"
    private String direction;                   // "LONG" | "SHORT"
    private double suggestedEntry;
    private double suggestedSl;
    private double suggestedTarget;
    private Instant signalTime;                 // when the bar that triggered closed
    private String timeframe;                   // "OneHour" | "Day"
    private Map<String, Object> extraContext;   // engine-specific (e.g. pattern_pivots for DTB)
}
