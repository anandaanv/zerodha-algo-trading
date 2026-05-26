package com.dtech.aitrader.v2.rules.confluence;

import com.dtech.aitrader.v2.rules.IndicatorAccessor;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.doReturn;

/**
 * ExhaustionDetector — owner memo {@code 34418c54} Q2 position. Tests use stub IndicatorAccessors
 * so the contract is independent of ta4j's RSI/MACD smoothing behaviour on a hand-crafted series.
 * The semantic predicate (price HH + RSI LH ⇒ bearish; |hist| shrinks ⇒ contraction) is what we
 * are locking — not the precise indicator value at a given index.
 */
class ExhaustionDetectorTest {

    @Test
    void rsi_bearish_divergence_detected_when_price_HH_rsi_LH() {
        // first close 100, second close 110 (HH). first RSI 75, second RSI 60 (LH) ⇒ bearish.
        IndicatorAccessor acc = stubAccessor(60,
                /*rsi at 30=*/ 75.0, /*rsi at 55=*/ 60.0,
                /*close at 30=*/ 100.0, /*close at 55=*/ 110.0);

        Optional<ExhaustionSignal> sig = ExhaustionDetector.detectRsiDivergence(
                acc, 30, 55, true, "Day");
        assertTrue(sig.isPresent(), "expected RSI bearish divergence");
        assertEquals("RSI_DIVERGENCE", sig.get().kind());
        assertEquals("BEARISH", sig.get().direction());
    }

    @Test
    void rsi_bullish_divergence_detected_when_price_LL_rsi_HL() {
        IndicatorAccessor acc = stubAccessor(60,
                /*rsi at 30=*/ 25.0, /*rsi at 55=*/ 40.0,
                /*close at 30=*/ 100.0, /*close at 55=*/ 90.0);

        Optional<ExhaustionSignal> sig = ExhaustionDetector.detectRsiDivergence(
                acc, 30, 55, false, "Day");
        assertTrue(sig.isPresent(), "expected RSI bullish divergence");
        assertEquals("BULLISH", sig.get().direction());
    }

    @Test
    void rsi_no_divergence_when_price_and_rsi_both_make_HH() {
        IndicatorAccessor acc = stubAccessor(60,
                /*rsi at 30=*/ 65.0, /*rsi at 55=*/ 75.0,
                /*close at 30=*/ 100.0, /*close at 55=*/ 110.0);

        Optional<ExhaustionSignal> sig = ExhaustionDetector.detectRsiDivergence(
                acc, 30, 55, true, "Day");
        assertTrue(sig.isEmpty(), "price HH + RSI HH ⇒ no bearish divergence");
    }

    @Test
    void macd_histogram_contraction_detected_when_abs_hist_shrinks() {
        // |hist(now)|=2, |hist(now-5)|=8 ⇒ contraction. LONG hypothesis ⇒ BEARISH direction.
        IndicatorAccessor acc = histAccessor(60, /*histNow=*/ 2.0, /*histPrev=*/ 8.0);

        Optional<ExhaustionSignal> sig = ExhaustionDetector.detectMacdHistogramContraction(
                acc, 59, "Day", "LONG");
        assertTrue(sig.isPresent(), "contracting |hist| should fire");
        assertEquals("BEARISH", sig.get().direction(),
                "LONG hypothesis exhausting up ⇒ BEARISH-direction signal");
    }

    @Test
    void macd_histogram_no_contraction_when_abs_hist_holds_or_grows() {
        // |hist| growing ⇒ no contraction signal.
        IndicatorAccessor acc = histAccessor(60, /*histNow=*/ 10.0, /*histPrev=*/ 5.0);

        Optional<ExhaustionSignal> sig = ExhaustionDetector.detectMacdHistogramContraction(
                acc, 59, "Day", "LONG");
        assertTrue(sig.isEmpty(), "growing |hist| must not fire contraction");
    }

    @Test
    void insufficient_bars_returns_empty() {
        IndicatorAccessor acc = histAccessor(10, 0.0, 0.0);
        Optional<ExhaustionSignal> sig = ExhaustionDetector.detectMacdHistogramContraction(
                acc, 4, "Day", "LONG");
        assertTrue(sig.isEmpty(), "currentIdx < MACD_LOOKBACK_BARS must return empty");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static BarSeries buildBars(int n, double[] closesOverride) {
        BarSeries s = new BaseBarSeriesBuilder().withName("test").build();
        Instant t0 = Instant.parse("2024-01-01T05:30:00Z");
        for (int i = 0; i < n; i++) {
            double c = closesOverride == null ? 100.0 : closesOverride[i];
            double o = c - 0.5;
            double h = c + 0.5;
            double l = c - 1.0;
            s.addBar(BarsLoader.getBar(o, h, l, c, 1_000.0,
                    t0.plus(Duration.ofHours(i + 1)), Duration.ofHours(1)));
        }
        return s;
    }

    /**
     * Spy accessor with hand-picked RSI values at the two query indices. The detector reads
     * close prices via {@link IndicatorAccessor#series()} — the series itself carries the chosen
     * closes at idx 30 and 55, so Mockito only needs to override the RSI return values.
     */
    private static IndicatorAccessor stubAccessor(int bars, double rsi1, double rsi2,
                                                    double close1, double close2) {
        double[] closes = new double[bars];
        for (int i = 0; i < bars; i++) closes[i] = 100.0;
        closes[30] = close1;
        closes[55] = close2;
        IndicatorAccessor spy = Mockito.spy(new IndicatorAccessor(buildBars(bars, closes)));
        doReturn(rsi1).when(spy).rsi(30);
        doReturn(rsi2).when(spy).rsi(55);
        return spy;
    }

    private static IndicatorAccessor histAccessor(int bars, double histNow, double histPrev) {
        BarSeries series = buildBars(bars, null);
        IndicatorAccessor spy = Mockito.spy(new IndicatorAccessor(series));
        int endIdx = series.getEndIndex();
        doReturn(histNow).when(spy).macdHistogram(endIdx);
        doReturn(histPrev).when(spy).macdHistogram(endIdx - ExhaustionDetector.MACD_LOOKBACK_BARS);
        return spy;
    }
}
