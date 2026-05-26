package com.dtech.aitrader.v2.rules;

import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Test;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Smoke test for {@link IndicatorAccessor} on a synthetic upward-trending series.
 *
 * <p>The point isn't to verify ta4j's math (ta4j has its own tests) — it's to verify (a) the
 * accessor wires every indicator correctly, (b) repeated accesses return consistent values
 * (the lazy cache is sound), (c) all 14 indicator paths are exercised at least once.
 */
class IndicatorAccessorTest {

    @Test
    void all_indicators_return_finite_values_on_uptrend_series() {
        BarSeries series = buildUptrend(300);
        IndicatorAccessor ind = new IndicatorAccessor(series);
        int end = series.getEndIndex();

        // Every accessor should produce a finite double — no NaN / no infinity / no throw.
        assertFinite(ind.macdLine(end), "macdLine");
        assertFinite(ind.macdSignal(end), "macdSignal");
        assertFinite(ind.macdHistogram(end), "macdHistogram");
        assertFinite(ind.rsi(end), "rsi");
        assertFinite(ind.adx(end), "adx");
        assertFinite(ind.plusDi(end), "plusDi");
        assertFinite(ind.minusDi(end), "minusDi");
        assertFinite(ind.ema20(end), "ema20");
        assertFinite(ind.ema50(end), "ema50");
        assertFinite(ind.ema200(end), "ema200");
        assertFinite(ind.atr(end), "atr");
        assertFinite(ind.bbMiddle(end), "bbMiddle");
        assertFinite(ind.bbUpper(end), "bbUpper");
        assertFinite(ind.bbLower(end), "bbLower");
        assertFinite(ind.bbWidth(end), "bbWidth");
    }

    @Test
    void lazy_cache_returns_same_value_on_repeated_access() {
        BarSeries series = buildUptrend(300);
        IndicatorAccessor ind = new IndicatorAccessor(series);
        int idx = series.getEndIndex() - 50;

        double rsi1 = ind.rsi(idx);
        double rsi2 = ind.rsi(idx);
        assertEquals(rsi1, rsi2, 0.0,
                "lazy cache must return identical values — no rebuild noise");

        double macd1 = ind.macdLine(idx);
        double macd2 = ind.macdLine(idx);
        assertEquals(macd1, macd2, 0.0);
    }

    @Test
    void ema_stack_is_ordered_correctly_on_uptrend() {
        // On a clean uptrend, EMA20 > EMA50 > EMA200 once the slow EMA has warmed up.
        BarSeries series = buildUptrend(400);
        IndicatorAccessor ind = new IndicatorAccessor(series);
        int end = series.getEndIndex();
        assertTrue(ind.ema20(end) > ind.ema50(end),
                "EMA20 should lead EMA50 on uptrend; got " + ind.ema20(end) + " vs " + ind.ema50(end));
        assertTrue(ind.ema50(end) > ind.ema200(end),
                "EMA50 should lead EMA200 on uptrend");
    }

    @Test
    void bull_cross_detector_handles_index_zero_safely() {
        BarSeries series = buildUptrend(100);
        IndicatorAccessor ind = new IndicatorAccessor(series);
        // Index 0 has no prior bar, so cross detection must short-circuit to false, not throw.
        assertFalse(ind.macdBullCrossAt(0));
        assertFalse(ind.macdBearCrossAt(0));
    }

    // ──────────────────────────────────────────────────────────────────────────

    private static BarSeries buildUptrend(int count) {
        BarSeries series = new BaseBarSeriesBuilder().withName("uptrend").build();
        long t0 = Instant.parse("2024-01-01T05:30:00Z").getEpochSecond();
        for (int i = 0; i < count; i++) {
            double price = 100.0 + i * 0.3;
            Bar bar = BarsLoader.getBar(price - 0.5, price + 1.0, price - 1.0, price, 1_000,
                    Instant.ofEpochSecond(t0 + i * 86_400L));
            series.addBar(bar);
        }
        return series;
    }

    private static void assertFinite(double v, String label) {
        assertFalse(Double.isNaN(v), label + " was NaN");
        assertFalse(Double.isInfinite(v), label + " was infinite");
    }
}
