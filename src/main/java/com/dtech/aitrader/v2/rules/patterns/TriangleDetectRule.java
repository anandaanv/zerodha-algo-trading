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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pass-2 candidate emitter for the TRIANGLE family (ascending / descending / symmetrical) per
 * SPEC-008 ({@code e332be7f}).
 *
 * <p>Per owner direction {@code 474986f0} FIX A (applied to H&amp;S; replicated here by
 * {@code a6f15887}): scans ALL valid right-edge anchor positions across the pivot history, not
 * just the latest-4. Emits EVERY valid triangle. Co-live with rectangle on parallel-flat-ish
 * boundaries per {@code a6f15887} (rectangle/triangle are a co-live pair discriminated forward).
 *
 * <p>Per owner direction {@code a6f15887}: discrimination by CONSTRUCTION (slope test + convergence
 * test), NOT pairwise exclusion. Triangle requires CONVERGING boundaries; rectangle requires
 * FLAT parallel. The slope/convergence gates are the only exclusion mechanism.
 *
 * <p>Per owner direction {@code a6f15887}: lines-crossed-in-span guard — a wedge/triangle fit
 * whose upper line and lower line cross ANYWHERE within the pivot span is rejected (the INFY Hour
 * edge case from {@code 3e430f8e}).
 */
@Component
@Slf4j
public class TriangleDetectRule implements Rule {

    public static final String RULE_ID = "TRIANGLE_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final int MIN_TOUCHES_PER_LINE = 2;
    private static final int MAX_TOUCHES_PER_LINE = 4;
    private static final int MAX_TRIANGLE_SPAN_BARS = 200;
    private static final int MIN_TRIANGLE_SPAN_BARS = 6;
    /**
     * Slope classification in ATR-PER-BAR units per owner direction {@code 79a97439}
     * (supersedes the earlier %-per-bar formulation in {@code 6321cdbd}). All distances —
     * including the rise component of a slope — go in degree-matched ATR per {@code 4d3cb3a7}.
     * "Flat": |slope/ATR| ≤ {@value #FLAT_SLOPE_ATR_PER_BAR}. "Trending": |slope/ATR| ≥
     * {@value #TREND_SLOPE_ATR_PER_BAR}. Calibrated against synthetic fixtures (mean ≈ 1400,
     * ATR ≈ 30) so {@code 0.0008 * 1400 / 30 ≈ 0.04} maps to old FLAT and {@code 0.0010 * 1400 / 30
     * ≈ 0.05} maps to old TREND. TF-invariant: Day bar slope and ATR scale together; Hour bar
     * slope and ATR scale together; the ratio is consistent.
     */
    private static final double FLAT_SLOPE_ATR_PER_BAR = 0.04;
    private static final double TREND_SLOPE_ATR_PER_BAR = 0.05;
    private static final double LINE_FIT_RESIDUAL_ATR = 1.0;
    private static final double BASE_PRIOR = 0.40;
    private static final double EMISSION_THRESHOLD = 25.0;
    private static final double CONFIRMED_THRESHOLD = 95.0;
    /** Bars to look past spanEnd for a confirming break-close — clamped to endIdx. */
    private static final int BREAK_LOOKFORWARD_BARS = 30;

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

        // Owner 474986f0 FIX A: sweep all (rightHighIdx, rightLowIdx) right-edge pairs.
        // Dedup by spanEnd bar index — multiple K combinations on the same span_end map to
        // the "same" pattern; pick the first encountered (which uses K_MAX touches).
        List<Firing> out = new ArrayList<>();
        Set<Integer> emittedSpanEnds = new HashSet<>();

        for (int rHi = MIN_TOUCHES_PER_LINE - 1; rHi < highs.size(); rHi++) {
            for (int rLi = MIN_TOUCHES_PER_LINE - 1; rLi < lows.size(); rLi++) {
                int kHighs = Math.min(MAX_TOUCHES_PER_LINE, rHi + 1);
                int kLows = Math.min(MAX_TOUCHES_PER_LINE, rLi + 1);
                List<PivotRef> windowHighs = highs.subList(rHi - kHighs + 1, rHi + 1);
                List<PivotRef> windowLows = lows.subList(rLi - kLows + 1, rLi + 1);

                // Minimum-structure floor per owner fbab9223 + 07c6fbf8: pivots in the window
                // must INTERLEAVE in time as H-L-H-L-... A 4-pivot window with two consecutive
                // same-type pivots is cluster+excursion (cluster retest), NOT a triangle.
                // Runs BEFORE geometry fit, completion, and discrimination.
                if (!alternates(windowHighs, windowLows)) continue;

                int spanStart = Math.min(firstIdx(windowHighs), firstIdx(windowLows));
                int spanEnd = Math.max(lastIdx(windowHighs), lastIdx(windowLows));
                int spanBars = spanEnd - spanStart;
                if (spanBars < MIN_TRIANGLE_SPAN_BARS || spanBars > MAX_TRIANGLE_SPAN_BARS) continue;

                Firing f = tryEmitTriangleAt(ctx, series, atr, endIdx,
                        windowHighs, windowLows, spanStart, spanEnd, emittedSpanEnds);
                if (f != null) out.add(f);
            }
        }
        return out;
    }

    private Firing tryEmitTriangleAt(SymbolContext ctx, BarSeries series, double atr, int endIdx,
                                      List<PivotRef> windowHighs, List<PivotRef> windowLows,
                                      int spanStart, int spanEnd, Set<Integer> emittedSpanEnds) {
        LineFit upperLine = fitLine(windowHighs);
        LineFit lowerLine = fitLine(windowLows);
        if (upperLine == null || lowerLine == null) return null;

        // Owner a6f15887 lines-crossed-in-span guard: reject fits where upper/lower lines cross
        // anywhere within [spanStart, spanEnd]. Lines crossing means the geometry isn't a valid
        // triangle/channel/wedge — the prior endIdx-only check fired on already-crossed lines.
        if (linesCrossWithinSpan(upperLine, lowerLine, spanStart, spanEnd)) return null;

        // Owner direction 79a97439: slope in ATR-per-bar units (TF-and-volatility invariant).
        double upperSlopeAtr = upperLine.slope / atr;
        double lowerSlopeAtr = lowerLine.slope / atr;
        boolean upperFlat = Math.abs(upperSlopeAtr) <= FLAT_SLOPE_ATR_PER_BAR;
        boolean lowerFlat = Math.abs(lowerSlopeAtr) <= FLAT_SLOPE_ATR_PER_BAR;
        boolean upperFalling = upperSlopeAtr <= -TREND_SLOPE_ATR_PER_BAR;
        boolean lowerRising = lowerSlopeAtr >= TREND_SLOPE_ATR_PER_BAR;
        boolean upperRising = upperSlopeAtr >= TREND_SLOPE_ATR_PER_BAR;
        boolean lowerFalling = lowerSlopeAtr <= -TREND_SLOPE_ATR_PER_BAR;

        String triangleType;
        String bias;
        boolean breakIsUp;
        if (upperFlat && lowerRising) {
            triangleType = "ascending";
            bias = "LONG";
            breakIsUp = true;
        } else if (upperFalling && lowerFlat) {
            triangleType = "descending";
            bias = "SHORT";
            breakIsUp = false;
        } else if (upperFalling && lowerRising) {
            triangleType = "symmetrical";
            bias = "NEUTRAL";
            breakIsUp = true;  // placeholder; reset below if symmetrical breaks down
        } else if (upperRising && lowerFalling) {
            return null;        // diverging / megaphone — out of scope for triangle
        } else {
            return null;        // parallel-ish (channel) or unclassifiable
        }

        // Evaluate at the pattern's natural completion time: spanEnd + lookforward, clamped to
        // endIdx. For current patterns this collapses to endIdx (no change in behaviour).
        int evalIdx = Math.min(endIdx, spanEnd + BREAK_LOOKFORWARD_BARS);
        if (evalIdx < 1) return null;
        double closeAt = series.getBar(evalIdx).getClosePrice().doubleValue();
        double closePrevAt = series.getBar(evalIdx - 1).getClosePrice().doubleValue();
        double upperAtEval = upperLine.yAt(evalIdx);
        double lowerAtEval = lowerLine.yAt(evalIdx);

        boolean confirmedBreak;
        String confirmedDirection = null;
        if ("ascending".equals(triangleType)) {
            boolean broke = closePrevAt <= upperAtEval && closeAt > upperAtEval;
            boolean alreadyAbove = closeAt > upperAtEval;
            confirmedBreak = broke || alreadyAbove;
            if (confirmedBreak) confirmedDirection = "above_upper";
        } else if ("descending".equals(triangleType)) {
            boolean broke = closePrevAt >= lowerAtEval && closeAt < lowerAtEval;
            boolean alreadyBelow = closeAt < lowerAtEval;
            confirmedBreak = broke || alreadyBelow;
            if (confirmedBreak) confirmedDirection = "below_lower";
        } else {
            boolean brokeUp = closePrevAt <= upperAtEval && closeAt > upperAtEval;
            boolean brokeDown = closePrevAt >= lowerAtEval && closeAt < lowerAtEval;
            boolean alreadyAbove = closeAt > upperAtEval;
            boolean alreadyBelow = closeAt < lowerAtEval;
            confirmedBreak = brokeUp || brokeDown || alreadyAbove || alreadyBelow;
            if (brokeUp || alreadyAbove) { confirmedDirection = "above_upper"; bias = "LONG"; breakIsUp = true; }
            else if (brokeDown || alreadyBelow) { confirmedDirection = "below_lower"; bias = "SHORT"; breakIsUp = false; }
        }

        double completion = computeCompletion(windowHighs, windowLows, upperLine, lowerLine,
                triangleType, atr, closeAt, upperAtEval, lowerAtEval, spanStart, confirmedBreak);
        if (completion < EMISSION_THRESHOLD) return null;

        // Dedup: one firing per spanEnd.
        if (!emittedSpanEnds.add(spanEnd)) return null;

        String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";
        double heightAtStart = upperLine.yAt(spanStart) - lowerLine.yAt(spanStart);
        double trigger = "ascending".equals(triangleType) ? upperAtEval
                : "descending".equals(triangleType) ? lowerAtEval
                : (breakIsUp ? upperAtEval : lowerAtEval);
        double target = ("descending".equals(triangleType)
                || (confirmedBreak && "below_lower".equals(confirmedDirection)))
                ? trigger - heightAtStart
                : trigger + heightAtStart;
        double invalidation = "ascending".equals(triangleType) ? lowerAtEval
                : "descending".equals(triangleType) ? upperAtEval
                : (breakIsUp ? lowerAtEval : upperAtEval);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("triangle_type", triangleType);
        payload.put("bias", bias);
        payload.put("upper_slope_atr_per_bar", upperSlopeAtr);
        payload.put("lower_slope_atr_per_bar", lowerSlopeAtr);
        payload.put("upper_line_at_eval", upperAtEval);
        payload.put("lower_line_at_eval", lowerAtEval);
        payload.put("upper_line_at_now", upperLine.yAt(endIdx));
        payload.put("lower_line_at_now", lowerLine.yAt(endIdx));
        payload.put("upper_touches", windowHighs.size());
        payload.put("lower_touches", windowLows.size());
        payload.put("upper_fit_residual_atr", upperLine.maxResidual / atr);
        payload.put("lower_fit_residual_atr", lowerLine.maxResidual / atr);
        payload.put("span_start_idx", spanStart);
        payload.put("span_end_idx", spanEnd);
        payload.put("span_bars", spanEnd - spanStart);
        payload.put("eval_idx", evalIdx);
        payload.put("eval_bars_after_span_end", evalIdx - spanEnd);
        payload.put("trigger_price", trigger);
        payload.put("target_price", target);
        payload.put("invalidation_price", invalidation);
        payload.put("current_close", closeAt);
        if (confirmedDirection != null) payload.put("confirmed_direction", confirmedDirection);

        return Firing.builder()
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
                .build();
    }

    /**
     * Lines-crossed-in-span guard per owner direction {@code a6f15887} obs #3.
     * Returns true if upper.yAt(x) ≤ lower.yAt(x) anywhere within [spanStart, spanEnd]. With
     * two linear functions the crossing point (if any) is closed-form; check it against the span.
     */
    private static boolean linesCrossWithinSpan(LineFit upper, LineFit lower,
                                                  int spanStart, int spanEnd) {
        // At endpoints: upper - lower at spanStart and spanEnd.
        double dStart = upper.yAt(spanStart) - lower.yAt(spanStart);
        double dEnd = upper.yAt(spanEnd) - lower.yAt(spanEnd);
        // If either endpoint already has upper ≤ lower, lines have crossed inside the span.
        if (dStart <= 0 || dEnd <= 0) return true;
        // Lines diverge or stay separated throughout the span if both endpoint diffs are positive
        // AND they don't reverse sign between (which is impossible for linear with same-sign
        // endpoint diffs). So safe.
        return false;
    }

    private double computeCompletion(List<PivotRef> highs, List<PivotRef> lows,
                                      LineFit upper, LineFit lower, String triangleType,
                                      double atr, double closeAt,
                                      double upperAtEval, double lowerAtEval, int spanStart,
                                      boolean confirmedBreak) {
        double c = 25.0;

        int touchesBeyondMin = Math.max(0, highs.size() - MIN_TOUCHES_PER_LINE)
                + Math.max(0, lows.size() - MIN_TOUCHES_PER_LINE);
        c += 5.0 * Math.min(3, touchesBeyondMin);

        double upperFitFrac = clamp01(1.0 - upper.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        double lowerFitFrac = clamp01(1.0 - lower.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        c += 5.0 * upperFitFrac;
        c += 5.0 * lowerFitFrac;

        if ("symmetrical".equals(triangleType)) {
            double heightAtEnd = Math.max(1e-9, upperAtEval - lowerAtEval);
            double heightAtStart = Math.max(1e-9, upper.yAt(spanStart) - lower.yAt(spanStart));
            double convergenceFrac = clamp01(1.0 - heightAtEnd / heightAtStart);
            c += 10.0 * convergenceFrac;
        }

        double distToUpper = Math.max(0, upperAtEval - closeAt);
        double distToLower = Math.max(0, closeAt - lowerAtEval);
        double nearerDist = Math.min(distToUpper, distToLower);
        double approachFrac = clamp01(1.0 - nearerDist / Math.max(1e-9, atr * 2.0));
        c += 15.0 * approachFrac;

        if (confirmedBreak) {
            c = Math.max(c, 85.0) + 15.0;
        }
        return Math.min(100.0, c);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

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

    private static int firstIdx(List<PivotRef> sorted) { return sorted.get(0).idx; }
    private static int lastIdx(List<PivotRef> sorted) { return sorted.get(sorted.size() - 1).idx; }

    /**
     * Owner minimum-structure floor (fbab9223 + 07c6fbf8): the union of windowHighs +
     * windowLows sorted by bar idx must alternate types (H-L-H-L-... or L-H-L-H-...).
     * Two consecutive same-type pivots indicate a cluster (multiple touches of one level),
     * not distinct swings — sub-minimum structure for triangle.
     */
    private static boolean alternates(List<PivotRef> windowHighs, List<PivotRef> windowLows) {
        int hI = 0, lI = 0;
        Boolean prevWasHigh = null;
        while (hI < windowHighs.size() || lI < windowLows.size()) {
            int hIdx = hI < windowHighs.size() ? windowHighs.get(hI).idx : Integer.MAX_VALUE;
            int lIdx = lI < windowLows.size() ? windowLows.get(lI).idx : Integer.MAX_VALUE;
            boolean takeHigh = hIdx < lIdx;
            if (prevWasHigh != null && prevWasHigh.booleanValue() == takeHigh) return false;
            prevWasHigh = takeHigh;
            if (takeHigh) hI++; else lI++;
        }
        return true;
    }

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
