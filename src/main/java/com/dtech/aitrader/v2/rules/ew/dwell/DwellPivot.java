package com.dtech.aitrader.v2.rules.ew.dwell;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * A dwell pivot per SPEC-009 ({@code 36b585f6}) + owner refinement ({@code f1201a45}): a
 * consolidation LEVEL formed by price hovering within a tight band for several bars without
 * making an ATR-sized reversal. NOT a swing pivot — distinct shape (multi-bar zone, not
 * single-instant turn) so it lives in its OWN collection on {@code SymbolContext.dwellPivots}
 * rather than being spliced into the alternating reversal-pivot series.
 *
 * <p>Detection per {@link DwellPivotDetector}: the maximal window {@code [startIdx..endIdx]}
 * where {@code max(highs) - min(lows) <= k * ATR} AND no single bar reverses by ≥
 * {@code atrMult * ATR}. A real shelf in a strong trend, structurally invisible to the
 * reversal-pivot zigzag.
 *
 * <p>Calibration validated on NIFTY 2025 daily ({@code 91b3a3f5}): k=0.8, N=3 yields ~3
 * real shelves per year — self-limiting by construction. Hr re-validated per Q3 of the
 * SPEC-009 brainstorm.
 */
@Value
@Builder
public class DwellPivot {

    /** Timeframe label, e.g. "Week" | "Day" | "OneHour". Matches the keys of {@code pivotsByTf}. */
    String tf;

    /** First bar of the dwell window (inclusive). */
    Instant startTimestamp;

    /** Last bar of the dwell window (inclusive). */
    Instant endTimestamp;

    /** Bar index of {@link #startTimestamp} in the source {@code BarSeries}. */
    int startIdx;

    /** Bar index of {@link #endTimestamp} in the source {@code BarSeries}. */
    int endIdx;

    /** Band centre = (bandHi + bandLo) / 2. The cluster-anchor price. */
    double centerPrice;

    /** Highest high inside the dwell window. */
    double bandHi;

    /** Lowest low inside the dwell window. */
    double bandLo;

    /** ATR at the dwell's start bar — the unit the {@code k}-band was measured against. */
    double atrUsed;

    /** Number of bars in the dwell window = endIdx - startIdx + 1. */
    int barCount;

    /**
     * Continuation-role label per {@code f1201a45}: HH = up-trend shelf, LL = down-trend shelf,
     * INDETERMINATE = post-dwell direction undecided within the lookforward window.
     * <p><b>NOT a swing identity claim.</b> See {@link Direction}.
     */
    Direction direction;
}
