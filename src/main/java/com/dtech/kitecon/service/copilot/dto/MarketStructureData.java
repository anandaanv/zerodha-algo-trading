package com.dtech.kitecon.service.copilot.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

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

    /** Human-readable summary for inclusion in AI prompt */
    public String toPromptSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Timeframe: %s | Trend: %s (strength: %d)\n", timeframe, trendDirection, trendStrength));
        if (lastSwingHigh != null) sb.append(String.format("Last Swing High: %.2f | Last Swing Low: %.2f\n", lastSwingHigh, lastSwingLow));
        if (prevSwingHigh != null) sb.append(String.format("Prev Swing High: %.2f | Prev Swing Low: %.2f\n", prevSwingHigh, prevSwingLow));
        if (lastEventWasBOS) sb.append("LAST EVENT: Break of Structure (BOS) — momentum continuation\n");
        if (lastEventWasCHoCH) sb.append("LAST EVENT: Change of Character (CHoCH) — potential reversal signal\n");
        sb.append("Recent swing sequence:\n");
        int start = Math.max(0, swingPoints.size() - 8);
        for (MarketStructurePoint p : swingPoints.subList(start, swingPoints.size())) {
            sb.append("  ").append(p.toDescription()).append("\n");
        }
        return sb.toString();
    }
}
