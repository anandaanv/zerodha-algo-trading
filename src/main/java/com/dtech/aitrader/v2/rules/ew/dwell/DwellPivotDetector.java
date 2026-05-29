package com.dtech.aitrader.v2.rules.ew.dwell;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.List;

/**
 * Dwell-pivot detector per SPEC-009 ({@code 36b585f6}) + dwell spec ({@code e603dbf9}) + owner
 * refinements ({@code 59fa728f}, {@code f1201a45}). Scans a bar series for maximal windows where
 * price stays inside a tight band without any single bar reversing by the zigzag threshold —
 * a consolidation-through-time shelf that the reversal-pivot zigzag structurally cannot see.
 *
 * <h2>Algorithm</h2>
 * Starting at bar {@code i}, extend forward to {@code j} while BOTH:
 * <ol>
 *   <li>{@code max(highs[i..j]) - min(lows[i..j]) <= k * ATR(i)} — band stays tight.</li>
 *   <li>No bar {@code m ∈ [i..j]} reverses by ≥ {@code atrMult * ATR(i)} — no real turn inside.
 *       Reversal magnitude per bar = {@code |close[m] - open[m]|}.</li>
 * </ol>
 * When the extension breaks (or hits end of series), if {@code (j - i + 1) >= minN}, emit a
 * single {@link DwellPivot} at the band-center for the maximal window {@code [i..j]} and
 * resume scanning from {@code j + 1}. Overlapping windows are collapsed by construction — one
 * dwell per region, matching the "self-limiting" intent owner emphasised.
 *
 * <h2>Direction inference (post-hoc)</h2>
 * After the dwell window ends at {@code j}, look forward {@code lookforwardBars} closes:
 * <ul>
 *   <li>If any post-dwell close breaks above {@code bandHi + directionBreakAtr * atr} →
 *       {@link Direction#HH}. The shelf was support in an uptrend.</li>
 *   <li>If any post-dwell close breaks below {@code bandLo - directionBreakAtr * atr} →
 *       {@link Direction#LL}. The shelf was resistance in a downtrend.</li>
 *   <li>If neither, or the dwell sits at the right edge of the data →
 *       {@link Direction#INDETERMINATE}.</li>
 * </ul>
 * Direction is a CONTINUATION-ROLE label per {@code f1201a45} — NOT a swing-high/low identity.
 *
 * <h2>Calibration</h2>
 * Defaults validated on NIFTY 2025 daily ({@code 91b3a3f5}): k=0.8, N=3 yields ~3 real shelves
 * per year. Hr requires per-N validation (Q3 of SPEC-009 brainstorm). The {@code atrMult} for
 * the no-reversal guard MUST match the zigzag's threshold to keep dwell + reversal definitions
 * consistent ({@code IncrementalZigZag} uses {@code ZigZagParams.atrMult}, typically 2.5–3.0
 * on daily).
 */
@Service
@Slf4j
public class DwellPivotDetector {

    /**
     * ATR period — simple SMA(14) of TR. We compute our own SMA-of-TR instead of using ta4j's
     * {@code ATRIndicator}: ta4j returns NaN during the first {@code period} bars and then seeds
     * Wilder smoothing from the first post-warmup TR alone (not from a SMA of warmup TRs), which
     * makes deterministic spec-derived tests on short synthetic series fragile. SMA(14) of TR is
     * stable from index 13 and matches owner's spec language ("ATR of degree" with no mention of
     * Wilder smoothing). EMA-smoothed variant remains an open refactor per {@code 4d3cb3a7}.
     */
    private static final int ATR_PERIOD = 14;

    /** Default post-dwell look-forward window for direction inference. */
    public static final int DEFAULT_LOOKFORWARD_BARS = 5;

    /** Default break threshold for direction inference, in ATR units (0.5 ATR break). */
    public static final double DEFAULT_DIRECTION_BREAK_ATR = 0.5;

    /**
     * Default band width (k) in ATR units. Validated 0.8 on NIFTY 2025 daily per
     * {@code 91b3a3f5}.
     */
    public static final double DEFAULT_K_BAND_ATR = 0.8;

    /**
     * Default minimum window length (N) in bars. Validated 3 on daily per {@code 91b3a3f5}.
     * Hr re-validation pending.
     */
    public static final int DEFAULT_MIN_N = 3;

    /**
     * Convenience entry point using {@link #DEFAULT_K_BAND_ATR} and {@link #DEFAULT_MIN_N}.
     * Suitable for daily detection. Use the parameterised overload for Hr or other TFs.
     */
    public List<DwellPivot> detect(BarSeries series, String tf, double atrMult) {
        return detect(series, tf, atrMult, DEFAULT_K_BAND_ATR, DEFAULT_MIN_N,
                DEFAULT_LOOKFORWARD_BARS, DEFAULT_DIRECTION_BREAK_ATR);
    }

    /**
     * Detect dwell pivots in the given series.
     *
     * @param series             oldest-first bar series for the target TF
     * @param tf                 timeframe label to stamp on each emitted DwellPivot
     * @param atrMult            no-reversal threshold in ATR units — MUST match the zigzag
     *                           detector's threshold ({@code ZigZagParams.atrMult}) for the
     *                           same TF to keep dwell and reversal definitions consistent
     * @param k                  band width in ATR units (default 0.8)
     * @param minN               minimum window length in bars (default 3 on daily)
     * @param lookforwardBars    bars to look forward for direction inference (default 5)
     * @param directionBreakAtr  break threshold for direction in ATR units (default 0.5)
     * @return time-ordered list of dwell pivots, possibly empty
     */
    public List<DwellPivot> detect(BarSeries series, String tf, double atrMult,
                                    double k, int minN,
                                    int lookforwardBars, double directionBreakAtr) {
        if (series == null) return List.of();
        int n = series.getBarCount();
        if (n < ATR_PERIOD + minN) return List.of();

        List<DwellPivot> out = new ArrayList<>();

        int i = ATR_PERIOD; // need ATR_PERIOD warm-up bars for SMA(TR)
        while (i <= n - minN) {
            double atrAtI = sma14OfTr(series, i);
            if (atrAtI <= 0) { i++; continue; }
            double bandMax = k * atrAtI;
            double reversalMin = atrMult * atrAtI;

            // Greedy extension: find the largest j s.t. [i..j] is a valid dwell window.
            int j = i;
            double bandHi = series.getBar(i).getHighPrice().doubleValue();
            double bandLo = series.getBar(i).getLowPrice().doubleValue();
            int firstViolator = -1;
            while (j + 1 < n) {
                Bar next = series.getBar(j + 1);
                double nh = next.getHighPrice().doubleValue();
                double nl = next.getLowPrice().doubleValue();
                double newHi = Math.max(bandHi, nh);
                double newLo = Math.min(bandLo, nl);
                // No-reversal guard: candle body magnitude (a bar reverses if |close - open| >= reversalMin).
                double bodyMag = Math.abs(next.getClosePrice().doubleValue()
                        - next.getOpenPrice().doubleValue());
                if (newHi - newLo > bandMax || bodyMag >= reversalMin) {
                    firstViolator = j + 1;
                    break;
                }
                bandHi = newHi;
                bandLo = newLo;
                j++;
            }

            int barCount = j - i + 1;
            if (barCount >= minN) {
                double center = (bandHi + bandLo) / 2.0;
                Direction direction = inferDirection(series, j, bandHi, bandLo,
                        atrAtI, lookforwardBars, directionBreakAtr);
                out.add(DwellPivot.builder()
                        .tf(tf)
                        .startTimestamp(series.getBar(i).getEndTime())
                        .endTimestamp(series.getBar(j).getEndTime())
                        .startIdx(i)
                        .endIdx(j)
                        .centerPrice(center)
                        .bandHi(bandHi)
                        .bandLo(bandLo)
                        .atrUsed(atrAtI)
                        .barCount(barCount)
                        .direction(direction)
                        .build());
                i = j + 1; // resume past the dwell
            } else {
                // Either no extension was possible, or the window violator forced an early break
                // before we hit minN. Advance to skip past the violator (or by 1 if at end).
                i = (firstViolator > 0) ? firstViolator : i + 1;
            }
        }
        log.debug("[dwell-detector] tf={} bars={} k={} N={} → {} dwell pivots",
                tf, n, k, minN, out.size());
        return out;
    }

    /**
     * Simple SMA of TR(14) at index {@code idx}. Stable from idx >= ATR_PERIOD - 1. TR per
     * bar = max(high-low, |high-prevClose|, |low-prevClose|). For idx 0 (no prev), TR = high - low.
     */
    private static double sma14OfTr(BarSeries series, int idx) {
        int start = Math.max(0, idx - ATR_PERIOD + 1);
        double sum = 0;
        int count = 0;
        for (int t = start; t <= idx; t++) {
            Bar bar = series.getBar(t);
            double high = bar.getHighPrice().doubleValue();
            double low = bar.getLowPrice().doubleValue();
            double tr;
            if (t == 0) {
                tr = high - low;
            } else {
                double prevClose = series.getBar(t - 1).getClosePrice().doubleValue();
                tr = Math.max(high - low,
                        Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            }
            sum += tr;
            count++;
        }
        return count > 0 ? sum / count : 0;
    }

    /**
     * Post-hoc direction inference: look forward from the dwell's end and report the first
     * decisive break beyond the band, in ATR units, per {@code f1201a45}.
     */
    private Direction inferDirection(BarSeries series, int endIdx, double bandHi, double bandLo,
                                       double atrAtStart, int lookforwardBars,
                                       double directionBreakAtr) {
        int n = series.getBarCount();
        if (endIdx + 1 >= n) return Direction.INDETERMINATE;
        double breakUp = bandHi + directionBreakAtr * atrAtStart;
        double breakDown = bandLo - directionBreakAtr * atrAtStart;
        int last = Math.min(endIdx + lookforwardBars, n - 1);
        for (int k = endIdx + 1; k <= last; k++) {
            double close = series.getBar(k).getClosePrice().doubleValue();
            if (close >= breakUp) return Direction.HH;
            if (close <= breakDown) return Direction.LL;
        }
        return Direction.INDETERMINATE;
    }
}
