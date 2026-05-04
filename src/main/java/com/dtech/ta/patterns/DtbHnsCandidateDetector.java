package com.dtech.ta.patterns;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.backtest.DetectedPattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.*;

/**
 * Pure-function detector for DTB (Double-Bottom/Double-Top) and HNS (Head-and-Shoulders) patterns
 * using candidate pivots (trailing extreme from IncrementalZigZag).
 *
 * Naming convention:
 *   P0 = latest pivot OR trailing-extreme candidate
 *   P1 = previous pivot
 *   P2 = previous-previous
 *   P3 = previous-previous-previous (HNS only)
 *
 * Input: confirmed pivots + trailing extreme (from IncrementalZigZag.getPivotsWithTrailingExtreme).
 * Output: List<DetectedPattern> with new fields: pivotP0, pivotP1, pivotP2, pivotP3.
 */
@Component
@Slf4j
public class DtbHnsCandidateDetector {

    /**
     * Detect DTB, DT, and HNS patterns from a pivot list that includes the trailing extreme as the last element.
     *
     * @param series BarSeries for context (optional, may be unused in pure pivot-based detection)
     * @param pivotsWithTrailingExtreme confirmed pivots + trailing extreme candidate at the end
     * @param atrArr array of ATR values indexed by bar
     * @param rsiValues array of RSI values indexed by bar
     * @param macdHistArr array of MACD histogram values indexed by bar
     * @param stochRsiK array of Stoch RSI K values indexed by bar
     * @param tsToIdx map from bar timestamp to bar index
     * @return list of detected patterns with new pivot fields populated
     */
    public List<DetectedPattern> detect(BarSeries series,
                                       List<ZigZagPoint> pivotsWithTrailingExtreme,
                                       double[] atrArr,
                                       double[] rsiValues,
                                       double[] macdHistArr,
                                       double[] stochRsiK,
                                       Map<Instant, Integer> tsToIdx) {
        List<DetectedPattern> results = new ArrayList<>();

        if (pivotsWithTrailingExtreme == null || pivotsWithTrailingExtreme.size() < 3) {
            return results;  // Need at least 3 pivots for DTB/DT
        }

        // The trailing extreme is at the end; P0 = pivots[size-1]
        int n = pivotsWithTrailingExtreme.size();

        // ────────────────────────────────────────────────────────────────
        // DOUBLE_BOTTOM and DOUBLE_TOP (need ≥ 3 pivots total)
        // ────────────────────────────────────────────────────────────────
        if (n >= 3) {
            ZigZagPoint p0 = pivotsWithTrailingExtreme.get(n - 1);
            ZigZagPoint p1 = pivotsWithTrailingExtreme.get(n - 2);
            ZigZagPoint p2 = pivotsWithTrailingExtreme.get(n - 3);

            // DOUBLE_BOTTOM: P0 low, P1 high, P2 low
            if (p0.isLow() && p1.isHigh() && p2.isLow()) {
                detectDoubleBo(p0, p1, p2, pivotsWithTrailingExtreme, atrArr, rsiValues,
                        macdHistArr, stochRsiK, tsToIdx, results, true);
            }

            // DOUBLE_TOP: P0 high, P1 low, P2 high
            if (p0.isHigh() && p1.isLow() && p2.isHigh()) {
                detectDoubleBo(p0, p1, p2, pivotsWithTrailingExtreme, atrArr, rsiValues,
                        macdHistArr, stochRsiK, tsToIdx, results, false);
            }
        }

        // ────────────────────────────────────────────────────────────────
        // HNS_BEAR and HNS_BULL (need ≥ 4 pivots total)
        // ────────────────────────────────────────────────────────────────
        if (n >= 4) {
            ZigZagPoint p0 = pivotsWithTrailingExtreme.get(n - 1);
            ZigZagPoint p1 = pivotsWithTrailingExtreme.get(n - 2);  // Head
            ZigZagPoint p2 = pivotsWithTrailingExtreme.get(n - 3);  // B (neckline low for bear, high for bull)
            ZigZagPoint p3 = pivotsWithTrailingExtreme.get(n - 4);  // LS (Left Shoulder)

            // HNS_BEAR: P3 high, P2 low, P1 high (head), P0 low candidate
            // Pattern: LS high > B low < Head high > B low (neckline) > RS low (P0)
            if (p3.isHigh() && p2.isLow() && p1.isHigh() && p0.isLow()) {
                detectHnsBear(p0, p1, p2, p3, pivotsWithTrailingExtreme, atrArr, rsiValues,
                        macdHistArr, stochRsiK, tsToIdx, results);
            }

            // HNS_BULL: P3 low, P2 high, P1 low (head), P0 high candidate
            // Pattern: LS low < B high > Head low < B high (neckline) < RS high (P0)
            if (p3.isLow() && p2.isHigh() && p1.isLow() && p0.isHigh()) {
                detectHnsBull(p0, p1, p2, p3, pivotsWithTrailingExtreme, atrArr, rsiValues,
                        macdHistArr, stochRsiK, tsToIdx, results);
            }
        }

        return results;
    }

    // ────────────────────────────────────────────────────────────────
    // DOUBLE_BOTTOM / DOUBLE_TOP
    // ────────────────────────────────────────────────────────────────

    private void detectDoubleBo(ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2,
                               List<ZigZagPoint> allPivots,
                               double[] atrArr, double[] rsiValues, double[] macdHistArr,
                               double[] stochRsiK, Map<Instant, Integer> tsToIdx,
                               List<DetectedPattern> results, boolean bullish) {
        double v0 = p0.getValue();
        double v1 = p1.getValue();
        double v2 = p2.getValue();

        // Retrace check: P0 sits in [61%, 99%] retrace of P2→P1 leg
        double leg = Math.abs(v1 - v2);
        if (leg <= 0) return;
        double retrace = Math.abs(v1 - v0) / leg;
        if (retrace < 0.61 || retrace > 0.99) return;

        // Get bar indices for RSI divergence check
        Integer p0Idx = tsToIdx.get(p0.getTimestamp());
        Integer p2Idx = tsToIdx.get(p2.getTimestamp());
        if (p0Idx == null || p2Idx == null) return;

        // Bounds check
        if (p0Idx >= rsiValues.length || p2Idx >= rsiValues.length) return;

        // RSI divergence: P0 should have higher RSI than P2 (both are same pivot type)
        double rsiP0 = rsiValues[p0Idx];
        double rsiP2 = rsiValues[p2Idx];
        if (rsiP0 <= rsiP2) return;  // No divergence

        // All checks passed — emit pattern
        Integer p1Idx = tsToIdx.get(p1.getTimestamp());
        if (p1Idx == null || p1Idx >= rsiValues.length) p1Idx = p0Idx;

        double patternHeight = Math.abs(v1 - Math.min(v0, v2));
        double target = bullish ? v1 + patternHeight : v1 - patternHeight;

        double rsiP1 = p1Idx < rsiValues.length ? rsiValues[p1Idx] : 0;
        double macdHistP1 = p1Idx < macdHistArr.length ? macdHistArr[p1Idx] : 0;
        double macdHistP2 = p2Idx < macdHistArr.length ? macdHistArr[p2Idx] : 0;
        double atrAtP0 = p0Idx < atrArr.length ? atrArr[p0Idx] : 0;
        double stochK = p0Idx < stochRsiK.length ? stochRsiK[p0Idx] : 0;

        double stopLoss = Math.min(v0, v2);  // DTB: min of two lows (DOUBLE_BOTTOM/DOUBLE_TOP)

        results.add(DetectedPattern.builder()
                .patternType(bullish ? "DOUBLE_BOTTOM" : "DOUBLE_TOP")
                .confirmationType(null)
                .bullish(bullish)
                .keyLevel(v1)                              // neckline = middle (bounce level)
                .keyLevelTime(p0.getTimestamp())           // detection time
                .entryPrice(0)                             // filled in Phase 2
                .stopLoss(stopLoss)                        // structural SL: min of P0, P2
                .ownTarget(target)
                .patternHeight(patternHeight)
                .atr(atrAtP0)
                .rsiAtP0(rsiP0)
                .rsiAtP1(rsiP1)
                .rsiAtP2(rsiP2)
                .macdHistAtP1(macdHistP1)
                .macdHistAtP2(macdHistP2)
                .stochRsiK(stochK)
                .dailyRsi(0)
                .macdHistogram(macdHistP2)
                .reversalPattern(null)
                // New fields for entry-confirmation flow
                .pivotP0(v0)
                .pivotP1(v1)
                .pivotP2(v2)
                .pivotP3(null)
                .build());
    }

    // ────────────────────────────────────────────────────────────────
    // HNS_BEAR (P3=LS high, P2=B low, P1=Head high, P0=RS low)
    // ────────────────────────────────────────────────────────────────

    private void detectHnsBear(ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2, ZigZagPoint p3,
                              List<ZigZagPoint> allPivots,
                              double[] atrArr, double[] rsiValues, double[] macdHistArr,
                              double[] stochRsiK, Map<Instant, Integer> tsToIdx,
                              List<DetectedPattern> results) {
        double ls = p3.getValue();   // LS high
        double b = p2.getValue();    // B low
        double head = p1.getValue(); // Head high
        double rs = p0.getValue();   // RS low (candidate)

        // Head must be higher than LS
        if (head <= ls) return;

        // B must be below both shoulders (neckline)
        if (b >= Math.min(ls, rs)) return;

        // Amplitude check: |Head - B| / |LS - B| ∈ [1.27, 2.0]
        double headLegSize = Math.abs(head - b);
        double lsLegSize = Math.abs(ls - b);
        if (lsLegSize <= 0) return;
        double amplitude = headLegSize / lsLegSize;
        if (amplitude < 1.27 || amplitude > 2.0) return;

        // Get bar indices
        Integer p0Idx = tsToIdx.get(p0.getTimestamp());
        Integer p1Idx = tsToIdx.get(p1.getTimestamp());
        Integer p2Idx = tsToIdx.get(p2.getTimestamp());
        Integer p3Idx = tsToIdx.get(p3.getTimestamp());

        if (p0Idx == null || p1Idx == null || p2Idx == null || p3Idx == null) return;

        // Bounds check
        if (p0Idx >= atrArr.length) return;

        double atrAtP0 = p0Idx < atrArr.length ? atrArr[p0Idx] : 0;

        // Compute measured move target
        double patternHeight = Math.abs(head - b);
        double target = b - patternHeight;

        double rsiP0 = p0Idx < rsiValues.length ? rsiValues[p0Idx] : 0;
        double rsiP1 = p1Idx < rsiValues.length ? rsiValues[p1Idx] : 0;
        double rsiP2 = p2Idx < rsiValues.length ? rsiValues[p2Idx] : 0;
        double macdHistP1 = p1Idx < macdHistArr.length ? macdHistArr[p1Idx] : 0;
        double macdHistP2 = p2Idx < macdHistArr.length ? macdHistArr[p2Idx] : 0;
        double stochK = p0Idx < stochRsiK.length ? stochRsiK[p0Idx] : 0;

        double stopLoss = head;  // HNS_BEAR: Head level invalidates pattern if breached upward

        results.add(DetectedPattern.builder()
                .patternType("HNS_BEAR")
                .confirmationType(null)
                .bullish(false)
                .keyLevel(b)                               // neckline at B level
                .keyLevelTime(p0.getTimestamp())           // detection time at RS
                .entryPrice(0)                             // filled in Phase 2
                .stopLoss(stopLoss)                        // structural SL: Head level (P1)
                .ownTarget(target)
                .patternHeight(patternHeight)
                .atr(atrAtP0)
                .rsiAtP0(rsiP0)
                .rsiAtP1(rsiP1)
                .rsiAtP2(rsiP2)
                .macdHistAtP1(macdHistP1)
                .macdHistAtP2(macdHistP2)
                .stochRsiK(stochK)
                .dailyRsi(0)
                .macdHistogram(macdHistP2)
                .reversalPattern(null)
                // New fields for entry-confirmation flow
                .pivotP0(rs)
                .pivotP1(head)
                .pivotP2(b)
                .pivotP3(ls)
                .build());
    }

    // ────────────────────────────────────────────────────────────────
    // HNS_BULL (P3=LS low, P2=B high, P1=Head low, P0=RS high)
    // ────────────────────────────────────────────────────────────────

    private void detectHnsBull(ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2, ZigZagPoint p3,
                              List<ZigZagPoint> allPivots,
                              double[] atrArr, double[] rsiValues, double[] macdHistArr,
                              double[] stochRsiK, Map<Instant, Integer> tsToIdx,
                              List<DetectedPattern> results) {
        double ls = p3.getValue();   // LS low
        double b = p2.getValue();    // B high
        double head = p1.getValue(); // Head low
        double rs = p0.getValue();   // RS high (candidate)

        // Head must be lower than LS
        if (head >= ls) return;

        // B must be above both shoulders (neckline)
        if (b <= Math.max(ls, rs)) return;

        // Amplitude check: |B - Head| / |B - LS| ∈ [1.27, 2.0]
        double headLegSize = Math.abs(b - head);
        double lsLegSize = Math.abs(b - ls);
        if (lsLegSize <= 0) return;
        double amplitude = headLegSize / lsLegSize;
        if (amplitude < 1.27 || amplitude > 2.0) return;

        // Get bar indices
        Integer p0Idx = tsToIdx.get(p0.getTimestamp());
        Integer p1Idx = tsToIdx.get(p1.getTimestamp());
        Integer p2Idx = tsToIdx.get(p2.getTimestamp());
        Integer p3Idx = tsToIdx.get(p3.getTimestamp());

        if (p0Idx == null || p1Idx == null || p2Idx == null || p3Idx == null) return;

        // Bounds check
        if (p0Idx >= atrArr.length) return;

        double atrAtP0 = p0Idx < atrArr.length ? atrArr[p0Idx] : 0;

        // Compute measured move target
        double patternHeight = Math.abs(b - head);
        double target = b + patternHeight;

        double rsiP0 = p0Idx < rsiValues.length ? rsiValues[p0Idx] : 0;
        double rsiP1 = p1Idx < rsiValues.length ? rsiValues[p1Idx] : 0;
        double rsiP2 = p2Idx < rsiValues.length ? rsiValues[p2Idx] : 0;
        double macdHistP1 = p1Idx < macdHistArr.length ? macdHistArr[p1Idx] : 0;
        double macdHistP2 = p2Idx < macdHistArr.length ? macdHistArr[p2Idx] : 0;
        double stochK = p0Idx < stochRsiK.length ? stochRsiK[p0Idx] : 0;

        double stopLoss = head;  // HNS_BULL: Head level invalidates pattern if breached downward

        results.add(DetectedPattern.builder()
                .patternType("HNS_BULL")
                .confirmationType(null)
                .bullish(true)
                .keyLevel(b)                               // neckline at B level
                .keyLevelTime(p0.getTimestamp())           // detection time at RS
                .entryPrice(0)                             // filled in Phase 2
                .stopLoss(stopLoss)                        // structural SL: Head level (P1)
                .ownTarget(target)
                .patternHeight(patternHeight)
                .atr(atrAtP0)
                .rsiAtP0(rsiP0)
                .rsiAtP1(rsiP1)
                .rsiAtP2(rsiP2)
                .macdHistAtP1(macdHistP1)
                .macdHistAtP2(macdHistP2)
                .stochRsiK(stochK)
                .dailyRsi(0)
                .macdHistogram(macdHistP2)
                .reversalPattern(null)
                // New fields for entry-confirmation flow
                .pivotP0(rs)
                .pivotP1(head)
                .pivotP2(b)
                .pivotP3(ls)
                .build());
    }
}
