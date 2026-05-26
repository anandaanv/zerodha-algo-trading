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
 * Pass-2 candidate emitter for the CHANNEL family per SPEC-008 ({@code e332be7f}): parallel
 * sloped trendlines, sloped same direction.
 *
 * <p>Per owner direction {@code 474986f0} FIX A + {@code a6f15887}: scans ALL valid right-edge
 * anchor positions, emits every valid channel. Discrimination by CONSTRUCTION — sloped parallel
 * required; flat → Rectangle; converging → Wedge. Lines-crossed-in-span guard rejects degenerate.
 */
@Component
@Slf4j
public class ChannelDetectRule implements Rule {

    public static final String RULE_ID = "CHANNEL_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final int MIN_TOUCHES_PER_LINE = 2;
    private static final int MAX_TOUCHES_PER_LINE = 4;
    private static final int MAX_CHANNEL_SPAN_BARS = 250;
    private static final int MIN_CHANNEL_SPAN_BARS = 6;
    private static final double MIN_CHANNEL_SLOPE_PCT_PER_BAR = 0.0005;
    private static final double MAX_SLOPE_DIFF_PCT_PER_BAR = 0.0005;
    private static final double MAX_CONVERGENCE_PCT = 0.20;
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
                if (spanBars < MIN_CHANNEL_SPAN_BARS || spanBars > MAX_CHANNEL_SPAN_BARS) continue;

                Firing f = tryEmitChannelAt(ctx, series, atr, endIdx,
                        windowHighs, windowLows, spanStart, spanEnd, emittedSpanEnds);
                if (f != null) out.add(f);
            }
        }
        return out;
    }

    private Firing tryEmitChannelAt(SymbolContext ctx, BarSeries series, double atr, int endIdx,
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

        boolean bothRising = upperSlopePct >= MIN_CHANNEL_SLOPE_PCT_PER_BAR
                && lowerSlopePct >= MIN_CHANNEL_SLOPE_PCT_PER_BAR;
        boolean bothFalling = upperSlopePct <= -MIN_CHANNEL_SLOPE_PCT_PER_BAR
                && lowerSlopePct <= -MIN_CHANNEL_SLOPE_PCT_PER_BAR;
        if (!bothRising && !bothFalling) return null;

        double slopeDiff = Math.abs(upperSlopePct - lowerSlopePct);
        if (slopeDiff > MAX_SLOPE_DIFF_PCT_PER_BAR) return null;

        double heightAtStart = upperLine.yAt(spanStart) - lowerLine.yAt(spanStart);
        double heightAtSpanEnd = upperLine.yAt(spanEnd) - lowerLine.yAt(spanEnd);
        if (heightAtStart <= 0 || heightAtSpanEnd <= 0) return null;
        double convergenceFrac = 1.0 - heightAtSpanEnd / heightAtStart;
        if (Math.abs(convergenceFrac) > MAX_CONVERGENCE_PCT) return null;

        String channelDirection = bothRising ? "up" : "down";
        String bias = bothRising ? "LONG" : "SHORT";

        int evalIdx = Math.min(endIdx, spanEnd + BREAK_LOOKFORWARD_BARS);
        if (evalIdx < 1) return null;
        double closeAt = series.getBar(evalIdx).getClosePrice().doubleValue();
        double upperAtEval = upperLine.yAt(evalIdx);
        double lowerAtEval = lowerLine.yAt(evalIdx);

        String channelState;
        boolean exited = false;
        if (closeAt > upperAtEval) {
            channelState = "exited_above";
            exited = true;
            if (bothFalling) bias = "LONG";
        } else if (closeAt < lowerAtEval) {
            channelState = "exited_below";
            exited = true;
            if (bothRising) bias = "SHORT";
        } else {
            channelState = "inside";
        }

        int totalTouches = windowHighs.size() + windowLows.size();
        boolean meetsConfirmedTouches = windowHighs.size() >= 3 && windowLows.size() >= 3;
        boolean confirmedBreak = exited || meetsConfirmedTouches;

        double completion = computeCompletion(windowHighs, windowLows, upperLine, lowerLine,
                slopeDiff, convergenceFrac, atr, closeAt, upperAtEval, lowerAtEval, confirmedBreak);
        if (completion < EMISSION_THRESHOLD) return null;
        if (!emittedSpanEnds.add(spanEnd)) return null;

        String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("channel_direction", channelDirection);
        payload.put("channel_state", channelState);
        payload.put("bias", bias);
        payload.put("upper_slope_pct_per_bar", upperSlopePct);
        payload.put("lower_slope_pct_per_bar", lowerSlopePct);
        payload.put("slope_diff_pct_per_bar", slopeDiff);
        payload.put("upper_line_at_eval", upperAtEval);
        payload.put("lower_line_at_eval", lowerAtEval);
        payload.put("upper_line_at_now", upperLine.yAt(endIdx));
        payload.put("lower_line_at_now", lowerLine.yAt(endIdx));
        payload.put("convergence_pct", convergenceFrac);
        payload.put("upper_touches", windowHighs.size());
        payload.put("lower_touches", windowLows.size());
        payload.put("upper_fit_residual_atr", upperLine.maxResidual / atr);
        payload.put("lower_fit_residual_atr", lowerLine.maxResidual / atr);
        payload.put("span_start_idx", spanStart);
        payload.put("span_end_idx", spanEnd);
        payload.put("span_bars", spanEnd - spanStart);
        payload.put("eval_idx", evalIdx);
        payload.put("eval_bars_after_span_end", evalIdx - spanEnd);
        if ("inside".equals(channelState)) {
            payload.put("trigger_price", bothRising ? upperAtEval : lowerAtEval);
            payload.put("invalidation_price", bothRising ? lowerAtEval : upperAtEval);
            payload.put("target_price", bothRising ? upperAtEval + 0.5 * heightAtStart
                    : lowerAtEval - 0.5 * heightAtStart);
        } else if ("exited_above".equals(channelState)) {
            payload.put("trigger_price", upperAtEval);
            payload.put("invalidation_price", lowerAtEval);
            payload.put("target_price", upperAtEval + heightAtStart);
        } else {
            payload.put("trigger_price", lowerAtEval);
            payload.put("invalidation_price", upperAtEval);
            payload.put("target_price", lowerAtEval - heightAtStart);
        }
        payload.put("current_close", closeAt);

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
                                      LineFit upper, LineFit lower,
                                      double slopeDiff, double convergenceFrac,
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
        double parallelismFrac = clamp01(1.0 - slopeDiff / MAX_SLOPE_DIFF_PCT_PER_BAR);
        c += 10.0 * parallelismFrac;
        double stabilityFrac = clamp01(1.0 - Math.abs(convergenceFrac) / MAX_CONVERGENCE_PCT);
        c += 10.0 * stabilityFrac;
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
