package com.dtech.aitrader.v2.rules.confluence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TargetProximityChecker — locks the deterministic ATR-multiple band per owner memo
 * {@code 34418c54} brainstorm-Q1. Default multiplier is 1.5×ATR (chosen because it matches the
 * pattern-detector equal-high tolerance — same scale across families).
 */
class TargetProximityCheckerTest {

    @Test
    void within_band_true_when_inside_atr_multiple() {
        // ATR=10, mult=1.5 → band=15. |current 1200 − target 1210| = 10 ≤ 15 → in band.
        assertTrue(TargetProximityChecker.withinBand(1200.0, 1210.0, 10.0, 1.5));
    }

    @Test
    void within_band_false_when_outside_atr_multiple() {
        // ATR=10, mult=1.5 → band=15. |current 1200 − target 1300| = 100 > 15 → out of band.
        assertFalse(TargetProximityChecker.withinBand(1200.0, 1300.0, 10.0, 1.5));
    }

    @Test
    void exact_target_is_in_band() {
        assertTrue(TargetProximityChecker.withinBand(1500.0, 1500.0, 5.0, 1.5));
    }

    @Test
    void zero_atr_disables_band_returns_false() {
        // Owner: proximity is geometric and ATR-scaled — zero/neg ATR is unusable. The rule must
        // not crash and must not silently treat "exact" as the only hit (that would mask data
        // problems). Choose false: emits no proximity firing rather than a spurious one.
        assertFalse(TargetProximityChecker.withinBand(1500.0, 1500.0, 0.0, 1.5));
    }
}
