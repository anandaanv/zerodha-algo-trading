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
 * Pass-2 candidate emitter for the FLAG / PENNANT family per SPEC-008 ({@code e332be7f}).
 *
 * <p>Per owner direction {@code 474986f0} FIX A + {@code a6f15887}: scans ALL valid poles
 * (one-leg %moves ≥ {@link #MIN_POLE_PCT} within MIN..MAX_POLE_BARS); for each pole, scans the
 * consolidation that follows. Discrimination by CONSTRUCTION: <b>flag requires a detectable
 * pole</b> per owner a6f15887 (the spurious "flag without pole" on RELIANCE Hr is killed by a
 * stricter pole gate). Pole gate now requires both %move ≥ MIN_POLE_PCT AND avg-per-bar move
 * ≥ MIN_POLE_AVG_PCT_PER_BAR — slow drifts of ≥5% over many bars no longer count as poles.
 */
@Component
@Slf4j
public class FlagPennantDetectRule implements Rule {

    public static final String RULE_ID = "FLAG_PENNANT_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final int MIN_POLE_BARS = 2;
    private static final int MAX_POLE_BARS = 12;
    /** Pole total %-move floor. */
    private static final double MIN_POLE_PCT = 0.05;
    /**
     * Owner origin-gate per {@code a6f15887}: a real pole is an IMPULSIVE leg — fast %move per
     * bar, not a slow drift. Requires avg %-move per bar ≥ this floor. Kills the spurious
     * RELIANCE Hour "flag-LONG" firing whose "pole" was a slow zigzag drift.
     */
    private static final double MIN_POLE_AVG_PCT_PER_BAR = 0.008;   // 0.8% per bar
    private static final int MIN_CONSOLIDATION_TOUCHES = 2;
    private static final int MAX_TOUCHES_PER_LINE = 4;
    private static final int MAX_CONSOLIDATION_BARS = 80;
    private static final int MIN_CONSOLIDATION_BARS = 4;
    /** Slope in ATR-per-bar units per owner direction {@code 79a97439}. */
    private static final double MIN_CONSOLIDATION_SLOPE_ATR_PER_BAR = 0.012;
    private static final double FLAG_SLOPE_DIFF_ATR_PER_BAR = 0.04;
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
        if (series == null || pivots == null || pivots.size() < 5) return List.of();
        int endIdx = series.getEndIndex();
        if (endIdx < 1) return List.of();

        ATRIndicator atrInd = new ATRIndicator(series, ATR_PERIOD);
        double atr = atrInd.getValue(endIdx).doubleValue();
        if (atr <= 0) return List.of();

        Map<Instant, Integer> indexer = indexer(series);
        List<PivotRef> sorted = sortAllPivots(pivots, indexer);
        if (sorted.size() < 5) return List.of();

        // Per FIX A + owner origin gate: scan ALL valid poles in the lookback. Each pole spawns
        // its own consolidation window scanned via sub-loop.
        List<PoleRef> poles = detectAllPoles(sorted);
        if (poles.isEmpty()) return List.of();

        List<Firing> out = new ArrayList<>();
        Set<Integer> emittedSpanEnds = new HashSet<>();
        for (PoleRef pole : poles) {
            List<Firing> emissions = tryEmitsForPole(ctx, series, atr, endIdx, sorted, pole, emittedSpanEnds);
            out.addAll(emissions);
        }
        return out;
    }

    private List<Firing> tryEmitsForPole(SymbolContext ctx, BarSeries series, double atr, int endIdx,
                                           List<PivotRef> sortedAll, PoleRef pole,
                                           Set<Integer> emittedSpanEnds) {
        List<PivotRef> consHighsAll = new ArrayList<>();
        List<PivotRef> consLowsAll = new ArrayList<>();
        for (PivotRef p : sortedAll) {
            if (p.idx <= pole.endIdx) continue;
            if (p.type == PivotType.HIGH) consHighsAll.add(p);
            else consLowsAll.add(p);
        }
        if (consHighsAll.size() < MIN_CONSOLIDATION_TOUCHES
                || consLowsAll.size() < MIN_CONSOLIDATION_TOUCHES) return List.of();

        List<Firing> out = new ArrayList<>();
        for (int rHi = MIN_CONSOLIDATION_TOUCHES - 1; rHi < consHighsAll.size(); rHi++) {
            for (int rLi = MIN_CONSOLIDATION_TOUCHES - 1; rLi < consLowsAll.size(); rLi++) {
                int kHighs = Math.min(MAX_TOUCHES_PER_LINE, rHi + 1);
                int kLows = Math.min(MAX_TOUCHES_PER_LINE, rLi + 1);
                List<PivotRef> windowHighs = consHighsAll.subList(rHi - kHighs + 1, rHi + 1);
                List<PivotRef> windowLows = consLowsAll.subList(rLi - kLows + 1, rLi + 1);

                // Minimum-structure floor per owner fbab9223 + 07c6fbf8: consolidation pivots
                // must alternate H-L-H-L-... A cluster + excursion masquerading as flag/pennant
                // is rejected here.
                if (!alternates(windowHighs, windowLows)) continue;

                int consStart = pole.endIdx;
                int consEnd = Math.max(lastIdx(windowHighs), lastIdx(windowLows));
                int consBars = consEnd - consStart;
                if (consBars < MIN_CONSOLIDATION_BARS || consBars > MAX_CONSOLIDATION_BARS) continue;

                Firing f = tryEmitFlagOrPennant(ctx, series, atr, endIdx, pole,
                        windowHighs, windowLows, consStart, consEnd, emittedSpanEnds);
                if (f != null) out.add(f);
            }
        }
        return out;
    }

    private Firing tryEmitFlagOrPennant(SymbolContext ctx, BarSeries series, double atr, int endIdx,
                                          PoleRef pole, List<PivotRef> consHighs, List<PivotRef> consLows,
                                          int consStart, int consEnd, Set<Integer> emittedSpanEnds) {
        LineFit upperLine = fitLine(consHighs);
        LineFit lowerLine = fitLine(consLows);
        if (upperLine == null || lowerLine == null) return null;

        // Lines-crossed-in-span guard (a6f15887 obs #3).
        double upperAtConsStart = upperLine.yAt(consStart);
        double lowerAtConsStart = lowerLine.yAt(consStart);
        double upperAtConsEnd = upperLine.yAt(consEnd);
        double lowerAtConsEnd = lowerLine.yAt(consEnd);
        if (upperAtConsStart <= lowerAtConsStart || upperAtConsEnd <= lowerAtConsEnd) return null;

        // Owner direction 79a97439: slope in ATR-per-bar units.
        double upperSlopeAtr = upperLine.slope / atr;
        double lowerSlopeAtr = lowerLine.slope / atr;

        boolean upPole = pole.up;
        boolean upperDown = upperSlopeAtr <= -MIN_CONSOLIDATION_SLOPE_ATR_PER_BAR;
        boolean upperUp = upperSlopeAtr >= MIN_CONSOLIDATION_SLOPE_ATR_PER_BAR;
        boolean lowerDown = lowerSlopeAtr <= -MIN_CONSOLIDATION_SLOPE_ATR_PER_BAR;
        boolean lowerUp = lowerSlopeAtr >= MIN_CONSOLIDATION_SLOPE_ATR_PER_BAR;

        boolean isFlag = false;
        boolean isPennant = false;
        if (upperDown && lowerUp) {
            isPennant = true;
        } else if (upPole && upperDown && lowerDown) {
            isFlag = true;
        } else if (!upPole && upperUp && lowerUp) {
            isFlag = true;
        }
        if (!isFlag && !isPennant) return null;

        double slopeDiff = Math.abs(upperSlopeAtr - lowerSlopeAtr);
        if (isFlag && slopeDiff > FLAG_SLOPE_DIFF_ATR_PER_BAR) return null;
        String patternType = isFlag ? "flag" : "pennant";

        int evalIdx = Math.min(endIdx, consEnd + BREAK_LOOKFORWARD_BARS);
        if (evalIdx < 1) return null;
        double closeAt = series.getBar(evalIdx).getClosePrice().doubleValue();
        double upperAtEval = upperLine.yAt(evalIdx);
        double lowerAtEval = lowerLine.yAt(evalIdx);

        String bias = upPole ? "LONG" : "SHORT";
        String consolidationState;
        boolean confirmed = false;
        if (upPole) {
            if (closeAt > upperAtEval) { consolidationState = "broken_up"; confirmed = true; }
            else if (closeAt < lowerAtEval) { consolidationState = "broken_down"; bias = "NEUTRAL"; }
            else consolidationState = "inside";
        } else {
            if (closeAt < lowerAtEval) { consolidationState = "broken_down"; confirmed = true; }
            else if (closeAt > upperAtEval) { consolidationState = "broken_up"; bias = "NEUTRAL"; }
            else consolidationState = "inside";
        }

        double completion = computeCompletion(consHighs, consLows, upperLine, lowerLine,
                atr, closeAt, upperAtEval, lowerAtEval, confirmed);
        if (completion < EMISSION_THRESHOLD) return null;
        if (!emittedSpanEnds.add(consEnd)) return null;

        String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";
        double poleHeight = Math.abs(pole.endPrice - pole.startPrice);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("pattern_type", patternType);
        payload.put("pole_direction", upPole ? "up" : "down");
        payload.put("pole_start_idx", pole.startIdx);
        payload.put("pole_end_idx", pole.endIdx);
        payload.put("pole_start_price", pole.startPrice);
        payload.put("pole_end_price", pole.endPrice);
        payload.put("pole_height", poleHeight);
        payload.put("pole_pct_move", pole.pctMove);
        payload.put("pole_avg_pct_per_bar", pole.avgPctPerBar);
        payload.put("consolidation_state", consolidationState);
        payload.put("bias", bias);
        payload.put("upper_line_at_eval", upperAtEval);
        payload.put("lower_line_at_eval", lowerAtEval);
        payload.put("upper_line_at_now", upperLine.yAt(endIdx));
        payload.put("lower_line_at_now", lowerLine.yAt(endIdx));
        payload.put("upper_slope_atr_per_bar", upperSlopeAtr);
        payload.put("lower_slope_atr_per_bar", lowerSlopeAtr);
        payload.put("slope_diff_atr_per_bar", slopeDiff);
        payload.put("upper_touches", consHighs.size());
        payload.put("lower_touches", consLows.size());
        payload.put("upper_fit_residual_atr", upperLine.maxResidual / atr);
        payload.put("lower_fit_residual_atr", lowerLine.maxResidual / atr);
        payload.put("span_start_idx", consStart);
        payload.put("span_end_idx", consEnd);
        payload.put("consolidation_bars", consEnd - consStart);
        payload.put("eval_idx", evalIdx);
        payload.put("eval_bars_after_span_end", evalIdx - consEnd);
        if (upPole) {
            payload.put("trigger_price", upperAtEval);
            payload.put("invalidation_price", lowerAtEval);
            payload.put("target_price", upperAtEval + poleHeight);
        } else {
            payload.put("trigger_price", lowerAtEval);
            payload.put("invalidation_price", upperAtEval);
            payload.put("target_price", lowerAtEval - poleHeight);
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

    /**
     * Owner a6f15887 origin gate: detect ALL valid poles (adjacent opposite-type pivot pairs) in
     * the lookback. A pole must clear BOTH thresholds — total %-move ≥ MIN_POLE_PCT AND
     * average %-per-bar ≥ MIN_POLE_AVG_PCT_PER_BAR (impulsive, not drifting).
     */
    private List<PoleRef> detectAllPoles(List<PivotRef> sorted) {
        List<PoleRef> poles = new ArrayList<>();
        for (int i = 0; i < sorted.size() - 1; i++) {
            PivotRef a = sorted.get(i);
            PivotRef b = sorted.get(i + 1);
            if (a.type == b.type) continue;
            int span = b.idx - a.idx;
            if (span < MIN_POLE_BARS || span > MAX_POLE_BARS) continue;
            double pct = Math.abs(b.price - a.price) / a.price;
            if (pct < MIN_POLE_PCT) continue;
            double avgPctPerBar = pct / span;
            if (avgPctPerBar < MIN_POLE_AVG_PCT_PER_BAR) continue;
            boolean up = b.price > a.price;
            poles.add(new PoleRef(a.idx, b.idx, a.price, b.price, pct, avgPctPerBar, up));
        }
        return poles;
    }

    private double computeCompletion(List<PivotRef> highs, List<PivotRef> lows,
                                      LineFit upper, LineFit lower,
                                      double atr, double closeAt,
                                      double upperAtEval, double lowerAtEval,
                                      boolean confirmedBreak) {
        double c = 25.0;
        int touchesBeyondMin = Math.max(0, highs.size() - MIN_CONSOLIDATION_TOUCHES)
                + Math.max(0, lows.size() - MIN_CONSOLIDATION_TOUCHES);
        c += 5.0 * Math.min(3, touchesBeyondMin);
        double upperFitFrac = clamp01(1.0 - upper.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        double lowerFitFrac = clamp01(1.0 - lower.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        c += 5.0 * upperFitFrac;
        c += 5.0 * lowerFitFrac;
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

    private static List<PivotRef> sortAllPivots(List<MarketStructurePoint> pivots,
                                                  Map<Instant, Integer> indexer) {
        List<PivotRef> collected = new ArrayList<>();
        for (MarketStructurePoint p : pivots) {
            Integer idx = indexer.get(p.getTimestamp());
            if (idx == null) continue;
            collected.add(new PivotRef(idx, p.getPrice(), p.getPivotType()));
        }
        collected.sort((a, b) -> Integer.compare(a.idx, b.idx));
        return collected;
    }

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

    private static Map<Instant, Integer> indexer(BarSeries series) {
        Map<Instant, Integer> m = new HashMap<>(series.getBarCount() * 2);
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            m.put(series.getBar(i).getEndTime(), i);
        }
        return m;
    }

    private record PivotRef(int idx, double price, PivotType type) { }

    private record LineFit(double slope, double intercept, double maxResidual) {
        double yAt(int idx) { return slope * idx + intercept; }
    }

    private record PoleRef(int startIdx, int endIdx, double startPrice, double endPrice,
                            double pctMove, double avgPctPerBar, boolean up) { }
}
