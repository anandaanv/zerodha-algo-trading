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
 * Pass-2 candidate emitter for the WEDGE family per SPEC-008 ({@code e332be7f}).
 *
 * <p>Per owner direction {@code 474986f0} FIX A + {@code a6f15887}: scans ALL valid right-edge
 * anchor positions, emits every valid wedge. Discrimination by CONSTRUCTION — both lines must
 * trend same direction with measurable convergence and slope-diff above the channel-parallel
 * gate. Lines-crossed-in-span guard rejects degenerate fits.
 */
@Component
@Slf4j
public class WedgeDetectRule implements Rule {

    public static final String RULE_ID = "WEDGE_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final int MIN_TOUCHES_PER_LINE = 2;
    private static final int MAX_TOUCHES_PER_LINE = 4;
    private static final int MAX_WEDGE_SPAN_BARS = 200;
    private static final int MIN_WEDGE_SPAN_BARS = 6;
    private static final double TREND_SLOPE_PCT_PER_BAR = 0.0002;
    private static final double MIN_CONVERGENCE_PCT = 0.20;
    private static final double WEDGE_SLOPE_DIFF_PCT_PER_BAR = 0.0005;
    private static final double LINE_FIT_RESIDUAL_ATR = 1.0;
    private static final double BASE_PRIOR = 0.40;
    private static final double EMISSION_THRESHOLD = 25.0;
    private static final double CONFIRMED_THRESHOLD = 95.0;
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

        List<Firing> out = new ArrayList<>();
        Set<Integer> emittedSpanEnds = new HashSet<>();

        for (int rHi = MIN_TOUCHES_PER_LINE - 1; rHi < highs.size(); rHi++) {
            for (int rLi = MIN_TOUCHES_PER_LINE - 1; rLi < lows.size(); rLi++) {
                int kHighs = Math.min(MAX_TOUCHES_PER_LINE, rHi + 1);
                int kLows = Math.min(MAX_TOUCHES_PER_LINE, rLi + 1);
                List<PivotRef> windowHighs = highs.subList(rHi - kHighs + 1, rHi + 1);
                List<PivotRef> windowLows = lows.subList(rLi - kLows + 1, rLi + 1);

                // Minimum-structure floor per owner fbab9223 + 07c6fbf8 — pivots must alternate.
                if (!alternates(windowHighs, windowLows)) continue;

                int spanStart = Math.min(firstIdx(windowHighs), firstIdx(windowLows));
                int spanEnd = Math.max(lastIdx(windowHighs), lastIdx(windowLows));
                int spanBars = spanEnd - spanStart;
                if (spanBars < MIN_WEDGE_SPAN_BARS || spanBars > MAX_WEDGE_SPAN_BARS) continue;

                Firing f = tryEmitWedgeAt(ctx, series, atr, endIdx,
                        windowHighs, windowLows, spanStart, spanEnd, emittedSpanEnds);
                if (f != null) out.add(f);
            }
        }
        return out;
    }

    private Firing tryEmitWedgeAt(SymbolContext ctx, BarSeries series, double atr, int endIdx,
                                    List<PivotRef> windowHighs, List<PivotRef> windowLows,
                                    int spanStart, int spanEnd, Set<Integer> emittedSpanEnds) {
        LineFit upperLine = fitLine(windowHighs);
        LineFit lowerLine = fitLine(windowLows);
        if (upperLine == null || lowerLine == null) return null;

        if (linesCrossWithinSpan(upperLine, lowerLine, spanStart, spanEnd)) return null;

        double upperMean = lineMeanPrice(windowHighs);
        double lowerMean = lineMeanPrice(windowLows);
        double upperSlopePct = upperMean > 0 ? upperLine.slope / upperMean : 0.0;
        double lowerSlopePct = lowerMean > 0 ? lowerLine.slope / lowerMean : 0.0;

        boolean bothRising = upperSlopePct >= TREND_SLOPE_PCT_PER_BAR
                && lowerSlopePct >= TREND_SLOPE_PCT_PER_BAR;
        boolean bothFalling = upperSlopePct <= -TREND_SLOPE_PCT_PER_BAR
                && lowerSlopePct <= -TREND_SLOPE_PCT_PER_BAR;
        if (!bothRising && !bothFalling) return null;

        double slopeDiff = Math.abs(upperSlopePct - lowerSlopePct);
        if (slopeDiff < WEDGE_SLOPE_DIFF_PCT_PER_BAR) return null;

        double heightAtStart = upperLine.yAt(spanStart) - lowerLine.yAt(spanStart);
        double heightAtSpanEnd = upperLine.yAt(spanEnd) - lowerLine.yAt(spanEnd);
        if (heightAtStart <= 0 || heightAtSpanEnd <= 0) return null;
        double convergence = 1.0 - heightAtSpanEnd / heightAtStart;
        if (convergence < MIN_CONVERGENCE_PCT) return null;

        String wedgeType = bothRising ? "rising" : "falling";
        String bias = bothRising ? "SHORT" : "LONG";

        int evalIdx = Math.min(endIdx, spanEnd + BREAK_LOOKFORWARD_BARS);
        if (evalIdx < 1) return null;
        double closeAt = series.getBar(evalIdx).getClosePrice().doubleValue();
        double closePrevAt = series.getBar(evalIdx - 1).getClosePrice().doubleValue();
        double upperAtEval = upperLine.yAt(evalIdx);
        double lowerAtEval = lowerLine.yAt(evalIdx);

        boolean confirmedBreak;
        String confirmedDirection = null;
        if ("rising".equals(wedgeType)) {
            boolean broke = closePrevAt >= lowerAtEval && closeAt < lowerAtEval;
            boolean alreadyBelow = closeAt < lowerAtEval;
            confirmedBreak = broke || alreadyBelow;
            if (confirmedBreak) confirmedDirection = "below_lower";
        } else {
            boolean broke = closePrevAt <= upperAtEval && closeAt > upperAtEval;
            boolean alreadyAbove = closeAt > upperAtEval;
            confirmedBreak = broke || alreadyAbove;
            if (confirmedBreak) confirmedDirection = "above_upper";
        }

        double completion = computeCompletion(windowHighs, windowLows, upperLine, lowerLine,
                convergence, atr, closeAt, upperAtEval, lowerAtEval, confirmedBreak);
        if (completion < EMISSION_THRESHOLD) return null;
        if (!emittedSpanEnds.add(spanEnd)) return null;

        String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";
        double trigger = "rising".equals(wedgeType) ? lowerAtEval : upperAtEval;
        double invalidation = "rising".equals(wedgeType) ? upperAtEval : lowerAtEval;
        double target = "rising".equals(wedgeType) ? trigger - heightAtStart : trigger + heightAtStart;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("wedge_type", wedgeType);
        payload.put("bias", bias);
        payload.put("upper_slope_pct_per_bar", upperSlopePct);
        payload.put("lower_slope_pct_per_bar", lowerSlopePct);
        payload.put("slope_diff_pct_per_bar", slopeDiff);
        payload.put("convergence_pct", convergence);
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
        payload.put("invalidation_price", invalidation);
        payload.put("target_price", target);
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

    private static boolean linesCrossWithinSpan(LineFit upper, LineFit lower,
                                                  int spanStart, int spanEnd) {
        double dStart = upper.yAt(spanStart) - lower.yAt(spanStart);
        double dEnd = upper.yAt(spanEnd) - lower.yAt(spanEnd);
        return dStart <= 0 || dEnd <= 0;
    }

    private double computeCompletion(List<PivotRef> highs, List<PivotRef> lows,
                                      LineFit upper, LineFit lower, double convergence,
                                      double atr, double closeAt,
                                      double upperAtEval, double lowerAtEval,
                                      boolean confirmedBreak) {
        double c = 25.0;
        int touchesBeyondMin = Math.max(0, highs.size() - MIN_TOUCHES_PER_LINE)
                + Math.max(0, lows.size() - MIN_TOUCHES_PER_LINE);
        c += 5.0 * Math.min(3, touchesBeyondMin);
        double upperFitFrac = clamp01(1.0 - upper.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        double lowerFitFrac = clamp01(1.0 - lower.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        c += 5.0 * upperFitFrac;
        c += 5.0 * lowerFitFrac;
        c += 10.0 * clamp01(convergence);
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
            sumX += p.idx; sumY += p.price;
            sumXY += p.idx * p.price; sumXX += p.idx * (double) p.idx;
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

    private static int firstIdx(List<PivotRef> sorted) { return sorted.get(0).idx; }
    private static int lastIdx(List<PivotRef> sorted) { return sorted.get(sorted.size() - 1).idx; }

    /** Owner minimum-structure floor (fbab9223 + 07c6fbf8): pivot sequence must alternate types. */
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
