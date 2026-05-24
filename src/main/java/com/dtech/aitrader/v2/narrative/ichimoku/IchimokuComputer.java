package com.dtech.aitrader.v2.narrative.ichimoku;

import com.dtech.chartdata.model.OhlcBarDTO;

import java.time.Instant;
import java.util.List;

/**
 * Self-implemented Ichimoku Kinko Hyo to keep the forward/back shift semantics explicit and
 * deterministic across ta4j versions. Five components:
 * <ul>
 *   <li>Tenkan-sen(9): midpoint of last 9 bars H/L.</li>
 *   <li>Kijun-sen(26): midpoint of last 26 bars H/L.</li>
 *   <li>Senkou Span A: (tenkan+kijun)/2, shifted forward {@code displacement} bars (so the
 *       value at index i reflects what was computed {@code displacement} bars earlier).</li>
 *   <li>Senkou Span B: midpoint of last 52 bars H/L, similarly shifted forward.</li>
 *   <li>Chikou Span: close shifted backward {@code displacement} bars (so the value at index i
 *       is bars[i+displacement].close, or NaN at the tail where it doesn't exist).</li>
 * </ul>
 *
 * <p>Forward-shifted means: the cloud value PLOTTED at bar i was computed from data at bar
 * i-displacement. So we store, at index i, the displacement-bars-old midpoint. For the very
 * first {@code displacement} bars there's no past data — we clamp to NaN-equivalent and fill
 * in the warmup pass.
 */
public final class IchimokuComputer {

    private IchimokuComputer() {}

    public static IchimokuSeries compute(List<OhlcBarDTO> bars, int tenkanPeriod, int kijunPeriod,
                                          int senkouBPeriod, int displacement,
                                          String symbol, String timeframe) {
        if (bars == null || bars.isEmpty()) {
            throw new IllegalArgumentException("bars must not be null or empty");
        }
        int n = bars.size();
        double[] highs = new double[n];
        double[] lows = new double[n];
        double[] closes = new double[n];
        Instant[] timestamps = new Instant[n];
        for (int i = 0; i < n; i++) {
            OhlcBarDTO b = bars.get(i);
            highs[i] = b.getHigh();
            lows[i] = b.getLow();
            closes[i] = b.getClose();
            timestamps[i] = Instant.ofEpochSecond(b.getTime());
        }
        double[] tenkan = midpointSeries(highs, lows, tenkanPeriod);
        double[] kijun = midpointSeries(highs, lows, kijunPeriod);
        double[] senkouAUnshifted = new double[n];
        for (int i = 0; i < n; i++) {
            senkouAUnshifted[i] = (tenkan[i] + kijun[i]) / 2.0;
        }
        double[] senkouBUnshifted = midpointSeries(highs, lows, senkouBPeriod);

        // Forward-shift cloud spans: at index i, store value-at-(i-displacement).
        double[] senkouA = forwardShift(senkouAUnshifted, displacement);
        double[] senkouB = forwardShift(senkouBUnshifted, displacement);

        // Backward-shift chikou: at index i, store close-at-(i+displacement) (or NaN at the tail).
        double[] chikou = new double[n];
        for (int i = 0; i < n; i++) {
            int src = i + displacement;
            chikou[i] = src < n ? closes[src] : Double.NaN;
        }

        return IchimokuSeries.builder()
                .tenkan(tenkan)
                .kijun(kijun)
                .senkouA(senkouA)
                .senkouB(senkouB)
                .chikou(chikou)
                .closes(closes)
                .barTimestamps(timestamps)
                .symbol(symbol)
                .timeframe(timeframe)
                .build();
    }

    private static double[] midpointSeries(double[] highs, double[] lows, int period) {
        int n = highs.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - period + 1);
            double hi = Double.NEGATIVE_INFINITY, lo = Double.POSITIVE_INFINITY;
            for (int j = start; j <= i; j++) {
                if (highs[j] > hi) hi = highs[j];
                if (lows[j] < lo) lo = lows[j];
            }
            out[i] = (hi + lo) / 2.0;
        }
        return out;
    }

    /** Forward-shift: at index i, return arr[i - shift]; for i < shift, return arr[0] (warmup clamp). */
    private static double[] forwardShift(double[] arr, int shift) {
        int n = arr.length;
        double[] out = new double[n];
        for (int i = 0; i < n; i++) {
            int src = i - shift;
            out[i] = src >= 0 ? arr[src] : arr[0];
        }
        return out;
    }
}
