package com.dtech.aitrader.v2.rules.ew.dwell;

import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Test;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spec-derived tests for {@link DwellPivotDetector}. Anchored to the dwell-pivot spec
 * ({@code e603dbf9}) + owner refinements ({@code 59fa728f}, {@code f1201a45}), NOT to the
 * detector's current output. Test failures are spec contracts — fix the code or the spec,
 * never weaken the test (per feedback {@code tests_from_requirements}).
 *
 * <p>Synthetic bars carry close=open with high=close+15, low=close-15 → TR = 30 per bar,
 * so ATR(14) ≈ 30 after the 14-bar warm-up. At default k=0.8, the band tolerance is
 * 24 pts. At atrMult=3.0 (matching daily {@code ZigZagParams.atrMult}), the no-reversal
 * threshold is 90 pts of candle-body magnitude.
 */
class DwellPivotDetectorTest {

    private static final Instant T0 = Instant.parse("2024-01-01T05:30:00Z");

    @Test
    void emits_single_dwell_for_3_bar_consolidation_at_default_k_and_N() {
        // Spec contract (e603dbf9): price within k*ATR for N bars → emit one DwellPivot.
        BarSeries s = warmupSeries(14, 1400.0);
        addQuietBar(s, 14, 1400.0);  // index 14
        addQuietBar(s, 15, 1400.0);  // index 15
        addQuietBar(s, 16, 1400.0);  // index 16 — minN=3 reached at indices [14..16]
        addQuietBar(s, 17, 1400.0);  // tail so detector can finalise the window

        List<DwellPivot> result = new DwellPivotDetector().detect(s, "Day", 3.0);

        assertEquals(1, result.size(), "exactly one dwell expected");
        DwellPivot dp = result.get(0);
        assertEquals("Day", dp.getTf());
        assertEquals(14, dp.getStartIdx());
        assertTrue(dp.getBarCount() >= 3, "barCount must reach the N=3 floor");
        assertEquals(1400.0, dp.getCenterPrice(), 1.0, "centre near 1400");
        assertTrue(dp.getBandHi() - dp.getBandLo() <= 0.8 * dp.getAtrUsed() + 0.01,
                "band must stay within k*ATR");
    }

    @Test
    void does_not_emit_when_band_exceeds_k_times_atr() {
        // Contract: bars whose combined high-low span exceeds k*ATR are NOT dwell.
        BarSeries s = warmupSeries(14, 1400.0);
        // Bars spanning ~40 pts (h=1420 / l=1380) > 0.8*30 = 24 pt band.
        addBar(s, 14, 1400.0, 1420.0, 1380.0, 1400.0);
        addBar(s, 15, 1400.0, 1420.0, 1380.0, 1400.0);
        addBar(s, 16, 1400.0, 1420.0, 1380.0, 1400.0);
        addBar(s, 17, 1400.0, 1420.0, 1380.0, 1400.0);

        List<DwellPivot> result = new DwellPivotDetector().detect(s, "Day", 3.0);

        assertEquals(0, result.size(), "band too wide → no dwell");
    }

    @Test
    void does_not_emit_when_a_bar_reverses_by_atrMult_times_atr() {
        // Contract: no-reversal guard. A bar whose |close-open| ≥ atrMult*ATR breaks the window.
        BarSeries s = warmupSeries(14, 1400.0);
        addQuietBar(s, 14, 1400.0);
        // index 15: body magnitude = |close-open| = 100 ≥ 3*30 = 90 → breaks window.
        addBar(s, 15, 1350.0, 1410.0, 1340.0, 1450.0);
        addQuietBar(s, 16, 1400.0);
        addQuietBar(s, 17, 1400.0);

        List<DwellPivot> result = new DwellPivotDetector().detect(s, "Day", 3.0);

        assertEquals(0, result.size(), "no-reversal guard must reject the window");
    }

    @Test
    void emits_one_maximal_dwell_for_five_qualifying_bars_not_multiple() {
        // Contract: greedy maximal-window scan. 5 qualifying bars emit ONE dwell with
        // barCount=5, NOT three overlapping 3-bar windows. This is the "self-limiting"
        // property the spec calls out (91b3a3f5). A volatile tail bar bounds the window.
        BarSeries s = warmupSeries(14, 1400.0);
        for (int i = 14; i < 19; i++) addQuietBar(s, i, 1400.0); // 5 quiet bars
        // Volatile tail: 60-pt span breaks the band → ends the window at idx 18.
        addBar(s, 19, 1400.0, 1430.0, 1370.0, 1400.0);

        List<DwellPivot> result = new DwellPivotDetector().detect(s, "Day", 3.0);

        assertEquals(1, result.size(), "exactly one maximal dwell");
        assertEquals(5, result.get(0).getBarCount(), "must span all 5 qualifying bars");
    }

    @Test
    void direction_HH_when_post_dwell_closes_break_above_band_by_break_threshold() {
        // Contract (f1201a45): post-dwell close above bandHi + 0.5*ATR → direction = HH.
        BarSeries s = warmupSeries(14, 1400.0);
        for (int i = 14; i < 17; i++) addQuietBar(s, i, 1400.0);
        // index 17: close at 1430 (= 1412 bandHi + ~0.5*30 ATR + margin) → triggers HH.
        addBar(s, 17, 1400.0, 1435.0, 1395.0, 1430.0);
        addBar(s, 18, 1430.0, 1440.0, 1420.0, 1435.0);

        List<DwellPivot> result = new DwellPivotDetector().detect(s, "Day", 3.0);

        assertEquals(1, result.size());
        assertEquals(Direction.HH, result.get(0).getDirection());
    }

    @Test
    void direction_LL_when_post_dwell_closes_break_below_band_by_break_threshold() {
        // Contract (f1201a45): post-dwell close below bandLo - 0.5*ATR → direction = LL.
        BarSeries s = warmupSeries(14, 1400.0);
        for (int i = 14; i < 17; i++) addQuietBar(s, i, 1400.0);
        // index 17: close at 1370 (= 1388 bandLo - ~0.5*30 ATR) → triggers LL.
        addBar(s, 17, 1400.0, 1405.0, 1365.0, 1370.0);
        addBar(s, 18, 1370.0, 1380.0, 1360.0, 1365.0);

        List<DwellPivot> result = new DwellPivotDetector().detect(s, "Day", 3.0);

        assertEquals(1, result.size());
        assertEquals(Direction.LL, result.get(0).getDirection());
    }

    @Test
    void direction_INDETERMINATE_when_dwell_sits_at_end_of_series() {
        // Contract (f1201a45): dwell at the right edge (no post-bars) → INDETERMINATE.
        BarSeries s = warmupSeries(14, 1400.0);
        for (int i = 14; i < 17; i++) addQuietBar(s, i, 1400.0);
        // No bars beyond the dwell window → cannot decide direction.

        List<DwellPivot> result = new DwellPivotDetector().detect(s, "Day", 3.0);

        assertEquals(1, result.size());
        assertEquals(Direction.INDETERMINATE, result.get(0).getDirection());
    }

    @Test
    void two_separate_dwells_emit_two_pivots() {
        // Contract: detector resumes after each dwell — two distinct quiet regions
        // separated by volatility must produce two DwellPivots.
        BarSeries s = warmupSeries(14, 1400.0);
        // Dwell #1 at 14..16
        for (int i = 14; i < 17; i++) addQuietBar(s, i, 1400.0);
        // Volatile bridge at 17..20 that breaks the band
        for (int i = 17; i < 21; i++) addBar(s, i, 1400.0, 1450.0, 1350.0, 1450.0);
        // Dwell #2 at 21..23
        for (int i = 21; i < 24; i++) addQuietBar(s, i, 1450.0);
        addQuietBar(s, 24, 1450.0);

        List<DwellPivot> result = new DwellPivotDetector().detect(s, "Day", 3.0);

        assertEquals(2, result.size(), "two separate dwells expected");
        assertTrue(result.get(0).getEndIdx() < result.get(1).getStartIdx(),
                "second dwell must come after first");
    }

    // ----- helpers -----

    /** Build a series with {@code n} warm-up bars at the given close, ready for ATR(14). */
    private static BarSeries warmupSeries(int n, double basePrice) {
        BarSeries s = new BaseBarSeriesBuilder().withName("dwell-test").build();
        for (int i = 0; i < n; i++) {
            addBar(s, i, basePrice, basePrice + 15.0, basePrice - 15.0, basePrice);
        }
        return s;
    }

    /**
     * A quiet bar: open=close=basePrice, high=base+8, low=base-8 → 16-pt span, well inside
     * the 0.8*ATR band even as Wilder smoothing decays ATR over a sequence of quiet bars
     * (warm-up bars have TR=30 → initial ATR≈30 → 0.8*ATR≈24 with margin against the 16-pt span).
     * Body = 0 ⇒ no-reversal guard trivially passes.
     */
    private static void addQuietBar(BarSeries s, int idx, double basePrice) {
        addBar(s, idx, basePrice, basePrice + 8.0, basePrice - 8.0, basePrice);
    }

    private static void addBar(BarSeries s, int idx, double o, double h, double l, double c) {
        s.addBar(BarsLoader.getBar(o, h, l, c, 1_000.0,
                T0.plus(Duration.ofHours(idx + 1)), Duration.ofHours(1)));
    }
}
