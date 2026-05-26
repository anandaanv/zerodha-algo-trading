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
 * Pass-2 candidate emitter for the WEDGE family per SPEC-008 ({@code e332be7f}):
 *
 * <ul>
 *   <li><b>Rising wedge (bearish)</b>: both upper + lower lines rising, but converging — lower
 *       rises faster than upper. Confirmation = breakdown below lower line. Bias = SHORT.</li>
 *   <li><b>Falling wedge (bullish)</b>: both lines falling, converging — upper falls faster.
 *       Confirmation = breakout above upper line. Bias = LONG.</li>
 * </ul>
 *
 * <p>Distinguishes from triangle (≥1 line flat) and channel (lines roughly parallel) by
 * requiring BOTH lines trending in the same direction AND meaningful convergence
 * ({@value #MIN_CONVERGENCE_PCT} narrowing across the span).
 *
 * <p>Per owner direction {@code 4a322dbe}: built on the current zigzag-pivot substrate;
 * candle-based re-platforming follows once enough patterns + test-cases land.
 */
@Component
@Slf4j
public class WedgeDetectRule implements Rule {

    public static final String RULE_ID = "WEDGE_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final int MIN_TOUCHES_PER_LINE = 2;
    private static final int MAX_WEDGE_SPAN_BARS = 200;
    private static final int MIN_WEDGE_SPAN_BARS = 6;
    /**
     * Slope threshold (percent-of-mean-price per bar) for "rising"/"falling" classification.
     * Lower than the triangle equivalent because a rising wedge's UPPER line is naturally
     * slow-rising by definition — if it were as fast as the lower line, it'd be a channel.
     * 0.02% per bar captures the slow side of the wedge while still excluding pure noise.
     */
    private static final double TREND_SLOPE_PCT_PER_BAR = 0.0002;
    /** Minimum convergence (1 - heightAtEnd/heightAtStart) for "wedge" vs "channel". */
    private static final double MIN_CONVERGENCE_PCT = 0.20;
    /** Slope difference between upper and lower (absolute pct/bar). Above = wedge; below = channel. */
    private static final double WEDGE_SLOPE_DIFF_PCT_PER_BAR = 0.0005;
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

        List<PivotRef> recentHighs = lastN(highs, 4);
        List<PivotRef> recentLows = lastN(lows, 4);
        int spanStart = Math.min(firstIdx(recentHighs), firstIdx(recentLows));
        int spanEnd = Math.max(lastIdx(recentHighs), lastIdx(recentLows));
        int spanBars = spanEnd - spanStart;
        if (spanBars < MIN_WEDGE_SPAN_BARS || spanBars > MAX_WEDGE_SPAN_BARS) return List.of();

        LineFit upperLine = fitLine(recentHighs);
        LineFit lowerLine = fitLine(recentLows);
        if (upperLine == null || lowerLine == null) return List.of();

        double upperMean = lineMeanPrice(recentHighs);
        double lowerMean = lineMeanPrice(recentLows);
        double upperSlopePct = upperMean > 0 ? upperLine.slope / upperMean : 0.0;
        double lowerSlopePct = lowerMean > 0 ? lowerLine.slope / lowerMean : 0.0;

        boolean upperRising = upperSlopePct >= TREND_SLOPE_PCT_PER_BAR;
        boolean lowerRising = lowerSlopePct >= TREND_SLOPE_PCT_PER_BAR;
        boolean upperFalling = upperSlopePct <= -TREND_SLOPE_PCT_PER_BAR;
        boolean lowerFalling = lowerSlopePct <= -TREND_SLOPE_PCT_PER_BAR;

        // Convergence check: height shrinks across the span.
        double heightAtStart = upperLine.yAt(spanStart) - lowerLine.yAt(spanStart);
        double heightAtEnd = upperLine.yAt(spanEnd) - lowerLine.yAt(spanEnd);
        if (heightAtStart <= 0 || heightAtEnd <= 0) return List.of();
        double convergenceFrac = 1.0 - heightAtEnd / heightAtStart;
        if (convergenceFrac < MIN_CONVERGENCE_PCT) return List.of();

        // Slope-difference check: ensure we're not mis-classifying a channel as a wedge.
        double slopeDiffPct = Math.abs(upperSlopePct - lowerSlopePct);
        if (slopeDiffPct < WEDGE_SLOPE_DIFF_PCT_PER_BAR) return List.of();

        String wedgeType;
        String bias;
        if (upperRising && lowerRising && lowerSlopePct > upperSlopePct) {
            wedgeType = "rising";
            bias = "SHORT";
        } else if (upperFalling && lowerFalling && upperSlopePct < lowerSlopePct) {
            wedgeType = "falling";
            bias = "LONG";
        } else {
            return List.of();   // neither rising nor falling wedge geometry
        }

        double upperAtEnd = upperLine.yAt(endIdx);
        double lowerAtEnd = lowerLine.yAt(endIdx);
        double trigger;
        double invalidation;
        boolean confirmedBreak;
        String confirmedDirection = null;
        if ("rising".equals(wedgeType)) {
            trigger = lowerAtEnd;
            invalidation = upperAtEnd;
            boolean broke = closePrev >= lowerAtEnd && closeNow < lowerAtEnd;
            boolean alreadyBelow = closeNow < lowerAtEnd;
            confirmedBreak = broke || alreadyBelow;
            if (confirmedBreak) confirmedDirection = "below_lower";
        } else {
            trigger = upperAtEnd;
            invalidation = lowerAtEnd;
            boolean broke = closePrev <= upperAtEnd && closeNow > upperAtEnd;
            boolean alreadyAbove = closeNow > upperAtEnd;
            confirmedBreak = broke || alreadyAbove;
            if (confirmedBreak) confirmedDirection = "above_upper";
        }

        double completion = computeCompletion(recentHighs, recentLows, upperLine, lowerLine,
                convergenceFrac, atr, closeNow, upperAtEnd, lowerAtEnd, confirmedBreak);
        if (completion < EMISSION_THRESHOLD) return List.of();

        String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";
        // Target = wedge height at the start projected from breakout point.
        double target = "rising".equals(wedgeType)
                ? trigger - heightAtStart
                : trigger + heightAtStart;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("wedge_type", wedgeType);
        payload.put("bias", bias);
        payload.put("upper_slope_pct_per_bar", upperSlopePct);
        payload.put("lower_slope_pct_per_bar", lowerSlopePct);
        payload.put("upper_line_at_now", upperAtEnd);
        payload.put("lower_line_at_now", lowerAtEnd);
        payload.put("convergence_pct", convergenceFrac);
        payload.put("upper_touches", recentHighs.size());
        payload.put("lower_touches", recentLows.size());
        payload.put("upper_fit_residual_atr", upperLine.maxResidual / atr);
        payload.put("lower_fit_residual_atr", lowerLine.maxResidual / atr);
        payload.put("span_bars", spanBars);
        payload.put("trigger_price", trigger);
        payload.put("invalidation_price", invalidation);
        payload.put("target_price", target);
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
                                      LineFit upper, LineFit lower, double convergenceFrac,
                                      double atr, double closeNow,
                                      double upperAtEnd, double lowerAtEnd,
                                      boolean confirmedBreak) {
        double c = 25.0;   // backbone established

        int touchesBeyondMin = Math.max(0, highs.size() - MIN_TOUCHES_PER_LINE)
                + Math.max(0, lows.size() - MIN_TOUCHES_PER_LINE);
        c += 5.0 * Math.min(3, touchesBeyondMin);

        double upperFitFrac = clamp01(1.0 - upper.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        double lowerFitFrac = clamp01(1.0 - lower.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        c += 5.0 * upperFitFrac;
        c += 5.0 * lowerFitFrac;

        // Convergence quality: tighter wedge = higher score. Linear from MIN_CONVERGENCE_PCT
        // (the gate, 0 score) to 0.80 (max score).
        double convQuality = clamp01((convergenceFrac - MIN_CONVERGENCE_PCT) / (0.80 - MIN_CONVERGENCE_PCT));
        c += 15.0 * convQuality;

        // Approach to break line (lower for rising, upper for falling).
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
            double res = Math.abs(p.price - (slope * p.idx + intercept));
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
