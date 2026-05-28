package com.dtech.aitrader.v2.rules.patterns.dataload;

import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

import java.util.ArrayList;
import java.util.List;

/**
 * <h2>RETIRED — owner directive {@code 60d21c43} (2026-05-28).</h2>
 * The candle-substrate path was retired after the calibration sweep {@code cf487bd1} proved
 * STRUCTURAL over-firing that knob-tuning (N, minSwingAtr) cannot fix: rectangle 45× zigzag
 * at strictest config, flag-pennant under-fires at same setting, no (N, minSwingAtr) brings
 * Day AND Hour to similar ratios. The candle DIRECTION was right (H&S detection retained at
 * every config — diagnostic {@code 43493549} resolved), but the swing-extraction PRIMITIVE was
 * wrong. The correct primitive is CLUSTER-RESPECT on the zigzag substrate (SPEC-009 {@code
 * 36b585f6}, build plan SPEC-010 {@code 3663d889}).
 *
 * <p>This class is NOT deleted — it stays as a candidate-source available to the cluster-respect
 * tests if needed, AND because the rollback tag {@code pattern-engine-zigzag-stable-v1} reasoning
 * depends on the Phase 2 history. {@code PATTERN_SUBSTRATE=zigzag} is the only supported value
 * going forward. Do not invoke this as the pattern primitive.
 *
 * <hr>
 *
 * <h2>Original intent (preserved for historical context):</h2>
 *
 * Candle-swing pivot extractor per owner direction {@code 4a322dbe} — picks swing highs/lows
 * directly from candle high/low values via a local-extremum scan, NOT from the smoothed zigzag.
 *
 * <p>Endpoint substrate for the pattern engine: the human chart-eye reads candle extremes
 * (e.g. RELIANCE shoulders at 1508/1473.4 that the zigzag treats as sub-noise — diagnostic
 * {@code 43493549}). This extractor produces a {@link MarketStructurePoint} stream the
 * pattern engine can consume in place of the canonical zigzag {@code pivotsByTf}.
 *
 * <p>Algorithm:
 * <ol>
 *   <li><b>Local-extremum scan</b>: for each bar i in [N, barCount-N), bar[i] is a swing HIGH
 *       iff its high price is the maximum in the window [i-N, i+N]; similarly for swing LOW.
 *       N = {@code lookbackN} (default 3).</li>
 *   <li><b>Alternation enforcement</b>: walking the candidates in time order, when two
 *       consecutive same-type swings appear, keep only the more extreme one (highest high or
 *       lowest low). This is the same H-L-H-L alternation the pattern engine's minimum-leg
 *       floor expects (owner {@code fbab9223}).</li>
 *   <li><b>Noise filter</b>: between adjacent opposite-type swings, require minimum price
 *       separation ≥ {@code minSwingAtr} × ATR. Smaller separations are intra-cluster noise
 *       and get dropped by collapsing back to the prior swing of the same kind.</li>
 * </ol>
 *
 * <p>Pattern-engine-isolated per {@code 89a52589}: this lives in the pattern dataload module;
 * EW continues to use the canonical zigzag pivots. Switching pattern detection to candle swings
 * happens at {@link PatternContextAttacher}'s attach step, governed by config/env.
 */
@Service
@Slf4j
public class CandleSwingExtractor {

    private static final int ATR_PERIOD = 14;
    /**
     * Default local-extremum lookback (bars on either side of a candidate swing). N=5 means
     * a swing must be the highest/lowest in an 11-bar window — a meaningful swing rather than
     * intra-bar noise. Smaller N produces many tiny swings; larger N misses the small forming
     * patterns owner wants (e.g. RELIANCE shoulders at non-pivot candle highs).
     */
    public static final int DEFAULT_LOOKBACK_N = 5;
    /**
     * Default minimum swing height between opposite swings, in ATR units. 1.0 ATR means the
     * price must move ≥ one bar's typical range to count as a swing. Tighter than the default
     * 0.5 to keep the swing-count tractable on multi-year Day series; tunes against the
     * Phase-1 blessed fixtures.
     */
    public static final double DEFAULT_MIN_SWING_ATR = 1.0;

    /** Convenience wrapper using {@link #DEFAULT_LOOKBACK_N} + {@link #DEFAULT_MIN_SWING_ATR}. */
    public List<MarketStructurePoint> extract(BarSeries series) {
        return extract(series, DEFAULT_LOOKBACK_N, DEFAULT_MIN_SWING_ATR);
    }

    /**
     * Extract candle-swing pivots from the bar series.
     *
     * @param series        ta4j bar series, oldest-first
     * @param lookbackN     bars on either side of a candidate for the local-extremum test
     * @param minSwingAtr   minimum opposite-swing separation (rise/fall) in degree-ATR units
     * @return time-ordered, alternating-by-type pivot list
     */
    public List<MarketStructurePoint> extract(BarSeries series, int lookbackN, double minSwingAtr) {
        if (series == null) return List.of();
        int n = series.getBarCount();
        if (n < 2 * lookbackN + 1) return List.of();

        ATRIndicator atrInd = new ATRIndicator(series, ATR_PERIOD);
        double atrAtEnd = atrInd.getValue(series.getEndIndex()).doubleValue();
        if (atrAtEnd <= 0) atrAtEnd = 1.0;

        // Pass 1: gather raw local-extremum candidates.
        List<Candidate> raw = new ArrayList<>();
        for (int i = lookbackN; i < n - lookbackN; i++) {
            Bar centre = series.getBar(i);
            double h = centre.getHighPrice().doubleValue();
            double l = centre.getLowPrice().doubleValue();
            boolean isHigh = true;
            boolean isLow = true;
            for (int j = i - lookbackN; j <= i + lookbackN; j++) {
                if (j == i) continue;
                Bar nb = series.getBar(j);
                double nh = nb.getHighPrice().doubleValue();
                double nl = nb.getLowPrice().doubleValue();
                if (nh > h) isHigh = false;
                if (nl < l) isLow = false;
            }
            if (isHigh) raw.add(new Candidate(i, h, PivotType.HIGH, centre, atrInd.getValue(i).doubleValue()));
            if (isLow) raw.add(new Candidate(i, l, PivotType.LOW, centre, atrInd.getValue(i).doubleValue()));
        }
        if (raw.isEmpty()) return List.of();

        // Pass 2: enforce alternation + minimum-swing-height noise filter in one walk.
        List<Candidate> filtered = new ArrayList<>();
        double minSwingPts = minSwingAtr * atrAtEnd;
        for (Candidate c : raw) {
            if (filtered.isEmpty()) { filtered.add(c); continue; }
            Candidate last = filtered.get(filtered.size() - 1);
            if (last.type == c.type) {
                // Same kind in a row: keep the more extreme.
                boolean replace = (c.type == PivotType.HIGH && c.price > last.price)
                        || (c.type == PivotType.LOW && c.price < last.price);
                if (replace) filtered.set(filtered.size() - 1, c);
                continue;
            }
            // Opposite kind: enforce minimum swing height.
            if (Math.abs(c.price - last.price) < minSwingPts) {
                // Too small a swing — drop this candidate. The next opposite-type after this
                // may still satisfy the gap; if a same-type appears next, keep-extreme handles it.
                continue;
            }
            filtered.add(c);
        }

        // Build MarketStructurePoint list. Use FIRST as the structureLabel — pattern rules
        // don't depend on Dow-Theory labelling here (that's an EW concern).
        List<MarketStructurePoint> out = new ArrayList<>(filtered.size());
        for (Candidate c : filtered) {
            out.add(MarketStructurePoint.builder()
                    .pivotType(c.type)
                    .structureLabel(StructureLabel.FIRST)
                    .timestamp(c.bar.getEndTime())
                    .price(c.price)
                    .atrAtPivot(c.atrAtPivot)
                    .rsiAtPivot(null)
                    .build());
        }
        log.debug("[candle-swing] extracted {} candle swings from {} bars (N={}, minSwing={}×ATR)",
                out.size(), n, lookbackN, minSwingAtr);
        return out;
    }

    private record Candidate(int idx, double price, PivotType type, Bar bar, double atrAtPivot) { }
}
