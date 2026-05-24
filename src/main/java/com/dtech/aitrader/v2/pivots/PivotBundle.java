package com.dtech.aitrader.v2.pivots;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * Carrier for a per-symbol pivot bundle: price zigzag pivots for every configured timeframe in
 * one record. Mirrors the role of {@code NarrativeBundle} but for the structural-pivot skeleton
 * that chart-pattern + Elliott Wave specialists reason over.
 *
 * <p>Owner b5ffa13f / 38339d28: "the pipeline's Step 1 (chart pattern) and Step 2 (Elliott Wave)
 * need the PIVOT BUNDLE — the detected swing highs/lows per symbol, one memory covering all
 * timeframes. Required content per timeframe: the ordered list of swing pivots."
 *
 * <p>The memsys body for this bundle is JSON (not pipe-table) so consumers can parse the pivots
 * directly; per-TF sections list pivots in chronological bar order.
 */
@Value
@Builder
public class PivotBundle {
    /** memsys tenant owner (resolved via JWT, not passed in tool args). */
    Long userId;

    /** Trading symbol. */
    String symbol;

    /** ISO date used in the {@code date-} tag. */
    String dateLabel;

    /**
     * Per-timeframe pivot lists keyed by TF label (weekly/daily/hourly/15min). Each value
     * carries its own bar count + last-bar date + ordered pivots. Linked-hash map preserves
     * the order weekly → daily → hourly → 15min.
     */
    Map<String, TimeframePivots> timeframes;

    /**
     * The forward-test cutoff date applied to this bundle (max common intraday last-bar date
     * across TFs in the bundle, or whichever cutoff policy was active). Same value gets stamped
     * as the {@code asof-<DATE>} tag.
     */
    String asOfDate;

    /** Per-TF pivot set + alignment metadata. */
    @Value
    @Builder
    public static class TimeframePivots {
        /** Bar count this TF was built on. */
        int barCount;
        /** ISO date of the last bar in this TF (post-cutoff alignment). */
        String lastBarDate;
        /** ISO date of bar 0. */
        String bar0Date;
        /** Pivots in chronological order (bar index ascending). */
        List<Pivot> pivots;
    }

    /** Single swing pivot — high or low — per the {@code DefaultSeriesPivotEngine} / ZigZag output. */
    @Value
    @Builder
    public static class Pivot {
        /** Bar index in this TF's series. */
        int idx;
        /** ISO date of the pivot's bar. */
        String date;
        /** Pivot kind: {@code "high"} or {@code "low"}. */
        String type;
        /** Pivot price (the swing extreme). */
        double price;
        /**
         * ATR at the pivot — proxy for "degree/strength" per owner's spec. Larger ATR = more
         * significant pivot in that TF's local volatility.
         */
        Double atrAtPivot;
    }
}
