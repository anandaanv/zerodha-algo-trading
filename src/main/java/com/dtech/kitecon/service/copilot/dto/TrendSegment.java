package com.dtech.kitecon.service.copilot.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * A contiguous trend segment derived from ZigZag market structure.
 *
 * Instead of per-pivot HH/HL/LH/LL noise, this captures coarser structural
 * narrative: "UPTREND from 95.20 to 108.50 over 8 pivots — ongoing."
 *
 * Used by:
 *  - MarketStructureData.toPromptSummary() — cleaner AI context
 *  - ElliottWaveAnalyzer — TfContext enrichment + W3/W2/W4 proportionality scoring
 */
@Data
@Builder
public class TrendSegment {

    public enum Direction { UPTREND, DOWNTREND }

    /** Direction of this segment */
    private Direction direction;

    /** Price of the first pivot in this segment */
    private double startPrice;

    /** Timestamp of the first pivot */
    private Instant startTime;

    /** Price of the last confirmed pivot in this segment */
    private double endPrice;

    /** Timestamp of the last pivot; null if ongoing */
    private Instant endTime;

    /** True if this is the most recent segment with no confirmed end */
    private boolean ongoing;

    /** Number of labeled ZigZag pivots that make up this segment */
    private int pivotCount;

    /** (endPrice - startPrice) / startPrice * 100 */
    private double priceChangePct;

    /** True if this segment ended because of a BOS (Break of Structure) event */
    private boolean endedByBOS;

    /** One-line summary for AI prompts */
    public String toSummary() {
        String change = String.format("%+.1f%%", priceChangePct);
        String end = ongoing
                ? "ongoing"
                : (endTime != null ? "ended " + endTime.toString().substring(0, 10) : "ended");
        String bos = endedByBOS ? " [BOS]" : "";
        return String.format("%-9s  %.2f → %.2f (%s) | %d pivots | %s%s",
                direction, startPrice, endPrice, change, pivotCount, end, bos);
    }
}
