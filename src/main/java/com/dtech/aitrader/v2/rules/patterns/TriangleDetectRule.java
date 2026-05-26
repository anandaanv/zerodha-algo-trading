package com.dtech.aitrader.v2.rules.patterns;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.ATRIndicator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-2 candidate emitter for the TRIANGLE family (ascending / descending / symmetrical) per
 * SPEC-008 ({@code e332be7f}). Detects three sub-types via the slopes of trendlines fit through
 * the recent HIGH + LOW pivots:
 *
 * <ul>
 *   <li><b>Ascending</b>: flat upper line (highs roughly equal) + rising lower line.
 *       Bias = LONG on upper-line break. Bullish continuation when in an uptrend.</li>
 *   <li><b>Descending</b>: falling upper line + flat lower line (lows roughly equal).
 *       Bias = SHORT on lower-line break.</li>
 *   <li><b>Symmetrical</b>: falling upper line + rising lower line (converging).
 *       Direction NOT assumed; breakout direction determines bias. Forming firings carry
 *       bias=NEUTRAL until the break.</li>
 * </ul>
 *
 * <p>Completion formula (continuous [0, 100]) — same shape as the H&amp;S detector:
 * <pre>
 *   ≥2 highs + ≥2 lows + valid sub-type classification    : +25
 *   touch count above baseline (each extra touch +5)      : +[0, 15]
 *   line fit quality (per-line residual within ATR band)  : +[0, 10]
 *   convergence tightness (symmetrical only; ratio)       : +[0, 10]
 *   approach to nearer line                               : +[0, 15]
 *   confirmed line break                                  : clamps to ≥85, +15 → 100
 * </pre>
 *
 * <p>Per owner direction {@code 4a322dbe}: built on the current zigzag-pivot substrate; the
 * candle-based re-platforming comes AFTER more patterns ship + working test-cases get captured.
 * Owner principle (general): textbook thresholds DOWN-WEIGHT, never EXCLUDE — soft slopes still
 * detect, scored lower.
 */
@Component
@Slf4j
public class TriangleDetectRule implements Rule {

    public static final String RULE_ID = "TRIANGLE_DETECT";

    private static final int ATR_PERIOD = 14;
    /** Minimum pivots per line to call it a triangle (per spec: 2 equal highs + 1-2 rising lows). */
    private static final int MIN_TOUCHES_PER_LINE = 2;
    /** Max scan window for triangle pivots (in bars). */
    private static final int MAX_TRIANGLE_SPAN_BARS = 200;
    private static final int MIN_TRIANGLE_SPAN_BARS = 6;
    /**
     * Slope classification thresholds in PERCENT-of-mean-price per bar (independent of ATR).
     * ATR-based slope was too sensitive to pivot-bar volatility — when pivot bars themselves
     * inject huge swings (as in a multi-month triangle), ATR balloons and "rising" slopes get
     * mis-classified as "flat". Percent-of-price is a stable reference.
     *
     * <p>"Flat" line: |slope/mean_price| ≤ 0.08% per bar. "Trending" line: ≥ 0.10% per bar.
     * The small gap between FLAT and TRENDING leaves a small "ambiguous" band that classifies
     * as neither — those patterns are rejected as not-classifiable.
     */
    private static final double FLAT_SLOPE_PCT_PER_BAR = 0.0008;
    private static final double TREND_SLOPE_PCT_PER_BAR = 0.0010;
    /** Line fit tolerance: residual / ATR must be ≤ this for "good fit". */
    private static final double LINE_FIT_RESIDUAL_ATR = 1.0;
    private static final double BASE_PRIOR = 0.40;
    private static final double EMISSION_THRESHOLD = 25.0;
    private static final double CONFIRMED_THRESHOLD = 95.0;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P2_ENUMERATION; }
    @Override public Family family() { return Family.PATTERN; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        BarSeries series = ctx.getSeries();
        List<MarketStructurePoint> pivots = ctx.getPivots();
        if (series == null || pivots == null || pivots.size() < 4) return List.of();
        int endIdx = series.getEndIndex();
        if (endIdx < 1) return List.of();

        ATRIndicator atrInd = new ATRIndicator(series, ATR_PERIOD);
        double atr = atrInd.getValue(endIdx).doubleValue();
        if (atr <= 0) return List.of();

        Map<Instant, Integer> indexer = indexer(series);
        List<PivotRef> highs = sortedPivotsOfType(pivots, indexer, PivotType.HIGH);
        List<PivotRef> lows = sortedPivotsOfType(pivots, indexer, PivotType.LOW);
        if (highs.size() < MIN_TOUCHES_PER_LINE || lows.size() < MIN_TOUCHES_PER_LINE) return List.of();

        double closeNow = series.getBar(endIdx).getClosePrice().doubleValue();
        double closePrev = series.getBar(endIdx - 1).getClosePrice().doubleValue();

        // Strategy: take the LATEST N highs + N lows (most-recent active triangle), fit lines,
        // classify. We don't scan every pair of windows back through time the way H&S does —
        // triangle is by nature a SINGLE active structure at the recent edge.
        List<PivotRef> recentHighs = lastN(highs, 4);
        List<PivotRef> recentLows = lastN(lows, 4);
        int spanStart = Math.min(firstIdx(recentHighs), firstIdx(recentLows));
        int spanEnd = Math.max(lastIdx(recentHighs), lastIdx(recentLows));
        int spanBars = spanEnd - spanStart;
        if (spanBars < MIN_TRIANGLE_SPAN_BARS || spanBars > MAX_TRIANGLE_SPAN_BARS) return List.of();

        LineFit upperLine = fitLine(recentHighs);
        LineFit lowerLine = fitLine(recentLows);
        if (upperLine == null || lowerLine == null) return List.of();

        // Classify sub-type by slope expressed as percent-of-mean-price per bar.
        double upperMean = lineMeanPrice(recentHighs);
        double lowerMean = lineMeanPrice(recentLows);
        double upperSlopePct = upperMean > 0 ? upperLine.slope / upperMean : 0.0;
        double lowerSlopePct = lowerMean > 0 ? lowerLine.slope / lowerMean : 0.0;
        boolean upperFlat = Math.abs(upperSlopePct) <= FLAT_SLOPE_PCT_PER_BAR;
        boolean lowerFlat = Math.abs(lowerSlopePct) <= FLAT_SLOPE_PCT_PER_BAR;
        boolean upperFalling = upperSlopePct <= -TREND_SLOPE_PCT_PER_BAR;
        boolean lowerRising = lowerSlopePct >= TREND_SLOPE_PCT_PER_BAR;
        boolean upperRising = upperSlopePct >= TREND_SLOPE_PCT_PER_BAR;
        boolean lowerFalling = lowerSlopePct <= -TREND_SLOPE_PCT_PER_BAR;

        String triangleType;
        String bias;
        double biasLineY;
        boolean breakIsUp;
        if (upperFlat && lowerRising) {
            triangleType = "ascending";
            bias = "LONG";
            biasLineY = upperLine.yAt(endIdx);
            breakIsUp = true;
        } else if (upperFalling && lowerFlat) {
            triangleType = "descending";
            bias = "SHORT";
            biasLineY = lowerLine.yAt(endIdx);
            breakIsUp = false;
        } else if (upperFalling && lowerRising) {
            triangleType = "symmetrical";
            bias = "NEUTRAL";
            biasLineY = closeNow > 0.5 * (upperLine.yAt(endIdx) + lowerLine.yAt(endIdx))
                    ? upperLine.yAt(endIdx)
                    : lowerLine.yAt(endIdx);
            breakIsUp = closeNow > 0.5 * (upperLine.yAt(endIdx) + lowerLine.yAt(endIdx));
        } else if (upperRising && lowerFalling) {
            // Diverging — broadening / megaphone, not a classical triangle. Reject here.
            return List.of();
        } else {
            // Parallel-ish (channel) — out of scope; rejected.
            return List.of();
        }

        // Confirmation: prior close on the inside; current close on the outside of the bias line.
        // Also counts "already outside" — close has been past the line for >1 bar, common when the
        // engine evaluates a few bars after the actual break.
        double upperAtEnd = upperLine.yAt(endIdx);
        double lowerAtEnd = lowerLine.yAt(endIdx);
        boolean confirmedBreak;
        String confirmedDirection = null;
        if ("ascending".equals(triangleType)) {
            boolean broke = closePrev <= upperAtEnd && closeNow > upperAtEnd;
            boolean alreadyAbove = closeNow > upperAtEnd;
            confirmedBreak = broke || alreadyAbove;
            if (confirmedBreak) confirmedDirection = "above_upper";
        } else if ("descending".equals(triangleType)) {
            boolean broke = closePrev >= lowerAtEnd && closeNow < lowerAtEnd;
            boolean alreadyBelow = closeNow < lowerAtEnd;
            confirmedBreak = broke || alreadyBelow;
            if (confirmedBreak) confirmedDirection = "below_lower";
        } else {
            // Symmetrical — break either way confirms; bias becomes break direction.
            boolean brokeUp = closePrev <= upperAtEnd && closeNow > upperAtEnd;
            boolean brokeDown = closePrev >= lowerAtEnd && closeNow < lowerAtEnd;
            boolean alreadyAbove = closeNow > upperAtEnd;
            boolean alreadyBelow = closeNow < lowerAtEnd;
            confirmedBreak = brokeUp || brokeDown || alreadyAbove || alreadyBelow;
            if (brokeUp || alreadyAbove) { confirmedDirection = "above_upper"; bias = "LONG"; }
            else if (brokeDown || alreadyBelow) { confirmedDirection = "below_lower"; bias = "SHORT"; }
        }

        double completion = computeCompletion(recentHighs, recentLows, upperLine, lowerLine,
                triangleType, atr, closeNow, upperAtEnd, lowerAtEnd, confirmedBreak);
        if (completion < EMISSION_THRESHOLD) return List.of();

        String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("triangle_type", triangleType);
        payload.put("bias", bias);
        payload.put("upper_slope_pct_per_bar", upperSlopePct);
        payload.put("lower_slope_pct_per_bar", lowerSlopePct);
        payload.put("upper_line_at_now", upperAtEnd);
        payload.put("lower_line_at_now", lowerAtEnd);
        payload.put("upper_touches", recentHighs.size());
        payload.put("lower_touches", recentLows.size());
        payload.put("upper_fit_residual_atr", upperLine.maxResidual / atr);
        payload.put("lower_fit_residual_atr", lowerLine.maxResidual / atr);
        payload.put("span_bars", spanBars);
        payload.put("trigger_price", "ascending".equals(triangleType) ? upperAtEnd
                : "descending".equals(triangleType) ? lowerAtEnd
                : (breakIsUp ? upperAtEnd : lowerAtEnd));
        // Target = pattern height projected from the breakout point. For ascending/symmetrical-up:
        // target = trigger + (upperAtFirstTouch - lowerAtFirstTouch); descending/symmetrical-down:
        // target = trigger - (upperAtFirstTouch - lowerAtFirstTouch).
        double heightAtStart = upperLine.yAt(spanStart) - lowerLine.yAt(spanStart);
        double trigger = ((Number) payload.get("trigger_price")).doubleValue();
        double target = ("descending".equals(triangleType)
                || (confirmedBreak && "below_lower".equals(confirmedDirection)))
                ? trigger - heightAtStart
                : trigger + heightAtStart;
        payload.put("target_price", target);
        // Invalidation: the opposite line.
        payload.put("invalidation_price", "ascending".equals(triangleType) ? lowerAtEnd
                : "descending".equals(triangleType) ? upperAtEnd
                : (breakIsUp ? lowerAtEnd : upperAtEnd));
        payload.put("current_close", closeNow);
        if (confirmedDirection != null) payload.put("confirmed_direction", confirmedDirection);

        return List.of(Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.PATTERN)
                .pass(Pass.P2_ENUMERATION)
                .firesOn(FiresOn.CANDIDATE)
                .basePrior(BASE_PRIOR)
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build());
    }

    private double computeCompletion(List<PivotRef> highs, List<PivotRef> lows,
                                      LineFit upper, LineFit lower, String triangleType,
                                      double atr, double closeNow,
                                      double upperAtEnd, double lowerAtEnd,
                                      boolean confirmedBreak) {
        double c = 25.0;  // backbone: ≥2 highs + ≥2 lows + valid classification

        // Touch count: each extra pivot beyond the minimum adds +5 (max +15).
        int touchesBeyondMin = Math.max(0, highs.size() - MIN_TOUCHES_PER_LINE)
                + Math.max(0, lows.size() - MIN_TOUCHES_PER_LINE);
        c += 5.0 * Math.min(3, touchesBeyondMin);

        // Line fit quality: per-line max residual relative to ATR. Lower = better.
        double upperFitFrac = clamp01(1.0 - upper.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        double lowerFitFrac = clamp01(1.0 - lower.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        c += 5.0 * upperFitFrac;
        c += 5.0 * lowerFitFrac;

        // Convergence tightness (symmetrical only): how close upper and lower are at the right
        // edge of the pattern, as a fraction of pattern height at the left edge.
        if ("symmetrical".equals(triangleType)) {
            double heightAtEnd = Math.max(1e-9, upperAtEnd - lowerAtEnd);
            int spanStart = Math.min(firstIdx(highs), firstIdx(lows));
            double heightAtStart = Math.max(1e-9, upper.yAt(spanStart) - lower.yAt(spanStart));
            double convergenceFrac = clamp01(1.0 - heightAtEnd / heightAtStart);
            c += 10.0 * convergenceFrac;
        }

        // Approach to the nearer breakout line — continuous, [0, 15].
        double distToUpper = Math.max(0, upperAtEnd - closeNow);
        double distToLower = Math.max(0, closeNow - lowerAtEnd);
        double nearerDist = Math.min(distToUpper, distToLower);
        double approachFrac = clamp01(1.0 - nearerDist / Math.max(1e-9, atr * 2.0));
        c += 15.0 * approachFrac;

        if (confirmedBreak) {
            c = Math.max(c, 85.0) + 15.0;
        }
        return Math.min(100.0, c);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    /**
     * Least-squares line fit through pivot bar indices. Returns {@code null} if fit fails (e.g.
     * single point or numerical issue). Captures slope, intercept, and the max absolute residual
     * across the input points — used by the completion's "fit quality" term.
     */
    private static LineFit fitLine(List<PivotRef> pivots) {
        int n = pivots.size();
        if (n < 2) return null;
        double sumX = 0, sumY = 0, sumXY = 0, sumXX = 0;
        for (PivotRef p : pivots) {
            sumX += p.idx;
            sumY += p.price;
            sumXY += p.idx * p.price;
            sumXX += p.idx * (double) p.idx;
        }
        double denom = n * sumXX - sumX * sumX;
        if (denom == 0) return null;
        double slope = (n * sumXY - sumX * sumY) / denom;
        double intercept = (sumY - slope * sumX) / n;
        double maxRes = 0;
        for (PivotRef p : pivots) {
            double predicted = slope * p.idx + intercept;
            double res = Math.abs(p.price - predicted);
            if (res > maxRes) maxRes = res;
        }
        return new LineFit(slope, intercept, maxRes);
    }

    private static double lineMeanPrice(List<PivotRef> pivots) {
        if (pivots.isEmpty()) return 0;
        double sum = 0;
        for (PivotRef p : pivots) sum += p.price;
        return sum / pivots.size();
    }

    private static List<PivotRef> lastN(List<PivotRef> sorted, int n) {
        if (sorted.size() <= n) return sorted;
        return sorted.subList(sorted.size() - n, sorted.size());
    }

    private static int firstIdx(List<PivotRef> sorted) { return sorted.get(0).idx; }
    private static int lastIdx(List<PivotRef> sorted) { return sorted.get(sorted.size() - 1).idx; }

    private static List<PivotRef> sortedPivotsOfType(List<MarketStructurePoint> pivots,
                                                       Map<Instant, Integer> indexer,
                                                       PivotType type) {
        List<PivotRef> collected = new ArrayList<>();
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() != type) continue;
            Integer idx = indexer.get(p.getTimestamp());
            if (idx == null) continue;
            collected.add(new PivotRef(idx, p.getPrice()));
        }
        collected.sort((a, b) -> Integer.compare(a.idx, b.idx));
        return collected;
    }

    private static Map<Instant, Integer> indexer(BarSeries series) {
        Map<Instant, Integer> m = new HashMap<>(series.getBarCount() * 2);
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            m.put(series.getBar(i).getEndTime(), i);
        }
        return m;
    }

    private record PivotRef(int idx, double price) { }

    private record LineFit(double slope, double intercept, double maxResidual) {
        double yAt(int idx) { return slope * idx + intercept; }
    }
}
