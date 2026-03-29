package com.dtech.kitecon.service.copilot.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Complete market structure analysis for one timeframe.
 * Replaces raw OHLCV data in AI prompts — AI gets maximum structural context
 * without needing to process raw candle data.
 */
@Data
@Builder
public class MarketStructureData {

    public enum TrendDirection { UPTREND, DOWNTREND, RANGING, UNDEFINED }

    private String timeframe;

    /** Classified and labeled swing points */
    private List<MarketStructurePoint> swingPoints;

    /** Current overall trend direction based on HH/HL or LH/LL sequence */
    private TrendDirection trendDirection;

    /** Number of consecutive HH/HL confirming the current uptrend (or LH/LL for downtrend) */
    private int trendStrength;

    /** Price of the most recent swing high */
    private Double lastSwingHigh;

    /** Price of the most recent swing low */
    private Double lastSwingLow;

    /** Price of the previous swing high (before lastSwingHigh) */
    private Double prevSwingHigh;

    /** Price of the previous swing low (before lastSwingLow) */
    private Double prevSwingLow;

    /** True if the last structure event was a BOS (clean break with momentum) */
    private boolean lastEventWasBOS;

    /** True if the last structure event was a CHoCH (first sign of reversal) */
    private boolean lastEventWasCHoCH;

    /**
     * Coarse trend segments (oldest first, max 6).
     * Each segment = a contiguous run of HH/HL (uptrend) or LH/LL (downtrend) pivots.
     * Last segment is always ongoing=true.
     */
    private List<TrendSegment> trendSegments;

    /** Human-readable summary for inclusion in AI prompt */
    public String toPromptSummary() {
        StringBuilder sb = new StringBuilder();

        // Header line
        String event = lastEventWasBOS ? " | Last event: BOS (momentum continuation)"
                     : lastEventWasCHoCH ? " | Last event: CHoCH (potential reversal)"
                     : "";
        sb.append(String.format("Timeframe: %s | Trend: %s (strength: %d)%s%n",
                timeframe, trendDirection, trendStrength, event));

        // Key levels
        if (lastSwingHigh != null) {
            sb.append(String.format("Key Levels: SwingH=%.2f  SwingL=%.2f", lastSwingHigh, lastSwingLow));
            if (prevSwingHigh != null)
                sb.append(String.format("  PrevH=%.2f  PrevL=%.2f", prevSwingHigh, prevSwingLow));
            sb.append("\n");
        }

        // Trend segments narrative (replaces noisy per-pivot dump)
        if (trendSegments != null && !trendSegments.isEmpty()) {
            sb.append("Trend segments (oldest → newest):\n");
            trendSegments.forEach(seg -> sb.append("  ").append(seg.toSummary()).append("\n"));
        }

        return sb.toString();
    }
}
