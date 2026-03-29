package com.dtech.ta.elliott;

import java.util.List;

/**
 * Analyzes the most recent decline-then-bounce sequence in a pivot list.
 *
 * Used by NestedCorrectiveContextBuilder to detect whether the current bounce
 * looks like a completed Wave B (45–65% retrace), which would imply the start
 * of Wave C / nested 1-2 accumulation (4C3 scenario).
 */
public class RetracementAnalyzer {

    public enum RetracementDegree {
        NONE,       // no meaningful bounce yet
        SHALLOW,    // < 38.2% — minor pullback
        B_WAVE,     // 38.2–65% — typical Wave B or Wave 2 retrace
        DEEP,       // 65–78.6% — deep retrace
        OVERSHOT    // > 78.6% — near-full retrace, different structure likely
    }

    public static class RetracementContext {

        public final double declineStart;   // price where the prior decline began (swing high)
        public final double declineEnd;     // price where the prior decline ended (swing low)
        public final double bounceLevel;    // current bounce price (last swing high after trough)
        public final double retracementPct; // 0.0–1.0+, fraction of decline recovered
        public final RetracementDegree degree;

        RetracementContext(double declineStart, double declineEnd, double bounceLevel) {
            this.declineStart = declineStart;
            this.declineEnd = declineEnd;
            this.bounceLevel = bounceLevel;
            double range = Math.abs(declineStart - declineEnd);
            this.retracementPct = range > 0
                    ? Math.abs(bounceLevel - declineEnd) / range
                    : 0.0;

            if (retracementPct < 0.20) {
                degree = RetracementDegree.NONE;
            } else if (retracementPct < 0.382) {
                degree = RetracementDegree.SHALLOW;
            } else if (retracementPct <= 0.65) {
                degree = RetracementDegree.B_WAVE;
            } else if (retracementPct <= 0.786) {
                degree = RetracementDegree.DEEP;
            } else {
                degree = RetracementDegree.OVERSHOT;
            }
        }

        /** True when the bounce is in the 38.2–65% zone — typical Wave B completion. */
        public boolean isBWaveLikely() {
            return degree == RetracementDegree.B_WAVE;
        }

        /** True when bounce is in 50–65% — classic 1-2 start-of-impulse retrace. */
        public boolean isNestedAccumulationLikely() {
            return retracementPct >= 0.50 && retracementPct <= 0.65;
        }
    }

    /**
     * Finds the last (peak → trough → bounce) sequence in the pivot list and returns
     * the retrace context. Returns null if the sequence cannot be reliably identified
     * or the decline is too shallow (< 5%).
     */
    public RetracementContext analyze(List<EnrichedPivot> pivots) {
        if (pivots == null || pivots.size() < 3) return null;

        // Step 1: find the last trough (LOW pivot)
        int troughIdx = -1;
        for (int i = pivots.size() - 1; i >= 0; i--) {
            if (pivots.get(i).isLow()) {
                troughIdx = i;
                break;
            }
        }
        if (troughIdx <= 0) return null;

        // Step 2: find the peak immediately before the trough (HIGH pivot)
        int peakIdx = -1;
        for (int i = troughIdx - 1; i >= 0; i--) {
            if (pivots.get(i).isHigh()) {
                peakIdx = i;
                break;
            }
        }
        if (peakIdx < 0) return null;

        double declineStart = pivots.get(peakIdx).getPrice();
        double declineEnd   = pivots.get(troughIdx).getPrice();

        // Require at least a 5% decline to be meaningful
        if ((declineStart - declineEnd) / declineStart < 0.05) return null;

        // Step 3: find the last HIGH pivot after the trough (the bounce)
        double bounceLevel = declineEnd; // default: no bounce recorded yet
        for (int i = troughIdx + 1; i < pivots.size(); i++) {
            if (pivots.get(i).isHigh()) {
                bounceLevel = pivots.get(i).getPrice();
            }
        }

        return new RetracementContext(declineStart, declineEnd, bounceLevel);
    }
}
