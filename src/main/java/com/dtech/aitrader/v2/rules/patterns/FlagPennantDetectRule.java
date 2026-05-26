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
 * Pass-2 candidate emitter for the FLAG / PENNANT family per SPEC-008 ({@code e332be7f}):
 * a fast directional <b>pole</b> followed by a counter-trend <b>consolidation</b>.
 *
 * <ul>
 *   <li><b>Flag</b>: consolidation lines parallel + counter-sloped to the pole.</li>
 *   <li><b>Pennant</b>: consolidation lines converge (symmetric triangle after the pole).</li>
 * </ul>
 *
 * <p>Bias = pole direction (continuation trade). Confirmation = close breaks the consolidation
 * boundary in the pole direction.
 *
 * <p><b>Zigzag-substrate caveat:</b> pole detection on pivots is approximate — we treat the
 * largest single pivot-to-pivot move within the lookback window as the pole. Candle-based
 * detection (deferred re-platform per owner direction {@code 4a322dbe}) will resolve this.
 */
@Component
@Slf4j
public class FlagPennantDetectRule implements Rule {

    public static final String RULE_ID = "FLAG_PENNANT_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final int MIN_POLE_BARS = 2;
    private static final int MAX_POLE_BARS = 12;
    /** Pole must be a strong directional move — at least 5% on the pivot leg. */
    private static final double MIN_POLE_PCT = 0.05;
    private static final int MIN_CONSOLIDATION_TOUCHES = 2;
    private static final int MAX_CONSOLIDATION_BARS = 80;
    /** Consolidation lines must be counter-sloped to the pole — magnitude floor. */
    private static final double MIN_CONSOLIDATION_SLOPE_PCT_PER_BAR = 0.0003;
    /** Flag vs pennant classification: slope diff < this = flag, else pennant. */
    private static final double FLAG_SLOPE_DIFF_PCT_PER_BAR = 0.0008;
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
        if (series == null || pivots == null || pivots.size() < 5) return List.of();
        int endIdx = series.getEndIndex();
        if (endIdx < 1) return List.of();

        ATRIndicator atrInd = new ATRIndicator(series, ATR_PERIOD);
        double atr = atrInd.getValue(endIdx).doubleValue();
        if (atr <= 0) return List.of();

        Map<Instant, Integer> indexer = indexer(series);
        List<PivotRef> sorted = sortAllPivots(pivots, indexer);
        if (sorted.size() < 5) return List.of();

        double closeNow = series.getBar(endIdx).getClosePrice().doubleValue();
        double closePrev = series.getBar(endIdx - 1).getClosePrice().doubleValue();

        // 1. Find the pole — the largest single pivot-to-pivot move within recent history.
        PoleRef pole = detectPole(sorted);
        if (pole == null) return List.of();

        // 2. Consolidation pivots = those AFTER pole.endIdx.
        List<PivotRef> consHighs = new ArrayList<>();
        List<PivotRef> consLows = new ArrayList<>();
        for (PivotRef p : sorted) {
            if (p.idx <= pole.endIdx) continue;
            if (p.type == PivotType.HIGH) consHighs.add(p);
            else consLows.add(p);
        }
        if (consHighs.size() < MIN_CONSOLIDATION_TOUCHES || consLows.size() < MIN_CONSOLIDATION_TOUCHES) {
            return List.of();
        }

        int consStart = pole.endIdx;
        int consEnd = Math.max(consHighs.get(consHighs.size() - 1).idx,
                consLows.get(consLows.size() - 1).idx);
        int consBars = consEnd - consStart;
        if (consBars > MAX_CONSOLIDATION_BARS) return List.of();

        LineFit upperLine = fitLine(consHighs);
        LineFit lowerLine = fitLine(consLows);
        if (upperLine == null || lowerLine == null) return List.of();

        double upperMean = lineMeanPrice(consHighs);
        double lowerMean = lineMeanPrice(consLows);
        double upperSlopePct = upperMean > 0 ? upperLine.slope / upperMean : 0.0;
        double lowerSlopePct = lowerMean > 0 ? lowerLine.slope / lowerMean : 0.0;

        boolean upPole = pole.up;
        boolean upperDown = upperSlopePct <= -MIN_CONSOLIDATION_SLOPE_PCT_PER_BAR;
        boolean upperUp = upperSlopePct >= MIN_CONSOLIDATION_SLOPE_PCT_PER_BAR;
        boolean lowerDown = lowerSlopePct <= -MIN_CONSOLIDATION_SLOPE_PCT_PER_BAR;
        boolean lowerUp = lowerSlopePct >= MIN_CONSOLIDATION_SLOPE_PCT_PER_BAR;

        // 3. Classify pattern by consolidation geometry + pole direction:
        //    • Flag: BOTH lines slope counter to pole (parallel counter-channel).
        //    • Pennant: upper falls + lower rises (symmetric triangle after pole, same shape
        //      regardless of pole direction).
        boolean isFlag = false;
        boolean isPennant = false;
        if (upperDown && lowerUp) {
            isPennant = true;                                  // converging triangle = pennant
        } else if (upPole && upperDown && lowerDown) {
            isFlag = true;                                     // bull flag — both down (parallel)
        } else if (!upPole && upperUp && lowerUp) {
            isFlag = true;                                     // bear flag — both up (parallel)
        }
        if (!isFlag && !isPennant) return List.of();

        double slopeDiff = Math.abs(upperSlopePct - lowerSlopePct);
        if (isFlag && slopeDiff > FLAG_SLOPE_DIFF_PCT_PER_BAR) {
            // Counter-sloped but not parallel — flag fails the parallelism gate.
            return List.of();
        }
        String patternType = isFlag ? "flag" : "pennant";

        double upperAtEnd = upperLine.yAt(endIdx);
        double lowerAtEnd = lowerLine.yAt(endIdx);
        if (upperAtEnd <= lowerAtEnd) return List.of();

        // 5. Bias = pole direction; confirmation = close breaks consolidation in pole direction.
        String bias = upPole ? "LONG" : "SHORT";
        String consolidationState;
        boolean confirmed = false;
        if (upPole) {
            if (closeNow > upperAtEnd) {
                consolidationState = "broken_up";
                confirmed = true;
            } else if (closeNow < lowerAtEnd) {
                consolidationState = "broken_down"; // counter to pole → failed flag
                bias = "NEUTRAL";
            } else {
                consolidationState = "inside";
            }
        } else {
            if (closeNow < lowerAtEnd) {
                consolidationState = "broken_down";
                confirmed = true;
            } else if (closeNow > upperAtEnd) {
                consolidationState = "broken_up"; // counter to pole → failed flag
                bias = "NEUTRAL";
            } else {
                consolidationState = "inside";
            }
        }

        double completion = computeCompletion(consHighs, consLows, upperLine, lowerLine,
                atr, closeNow, upperAtEnd, lowerAtEnd, confirmed);
        if (completion < EMISSION_THRESHOLD) return List.of();

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
        payload.put("consolidation_state", consolidationState);
        payload.put("bias", bias);
        payload.put("upper_line_at_now", upperAtEnd);
        payload.put("lower_line_at_now", lowerAtEnd);
        payload.put("upper_slope_pct_per_bar", upperSlopePct);
        payload.put("lower_slope_pct_per_bar", lowerSlopePct);
        payload.put("slope_diff_pct_per_bar", slopeDiff);
        payload.put("upper_touches", consHighs.size());
        payload.put("lower_touches", consLows.size());
        payload.put("upper_fit_residual_atr", upperLine.maxResidual / atr);
        payload.put("lower_fit_residual_atr", lowerLine.maxResidual / atr);
        payload.put("consolidation_bars", consBars);
        // Trigger = pole-direction edge; invalidation = opposite edge; target = pole height.
        if (upPole) {
            payload.put("trigger_price", upperAtEnd);
            payload.put("invalidation_price", lowerAtEnd);
            payload.put("target_price", upperAtEnd + poleHeight);
        } else {
            payload.put("trigger_price", lowerAtEnd);
            payload.put("invalidation_price", upperAtEnd);
            payload.put("target_price", lowerAtEnd - poleHeight);
        }
        payload.put("current_close", closeNow);

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

    /**
     * Detect the most prominent pole among adjacent pivot pairs — the longest %move
     * over an acceptable span. Picks the most recent qualifying pole if multiple exist.
     */
    private PoleRef detectPole(List<PivotRef> sorted) {
        PoleRef best = null;
        for (int i = 0; i < sorted.size() - 1; i++) {
            PivotRef a = sorted.get(i);
            PivotRef b = sorted.get(i + 1);
            if (a.type == b.type) continue;
            int span = b.idx - a.idx;
            if (span < MIN_POLE_BARS || span > MAX_POLE_BARS) continue;
            double pct = Math.abs(b.price - a.price) / a.price;
            if (pct < MIN_POLE_PCT) continue;
            boolean up = b.price > a.price;
            PoleRef candidate = new PoleRef(a.idx, b.idx, a.price, b.price, pct, up);
            // Keep the most recent qualifying pole — gives us the leg that just printed.
            if (best == null || candidate.endIdx > best.endIdx) best = candidate;
        }
        return best;
    }

    private double computeCompletion(List<PivotRef> highs, List<PivotRef> lows,
                                      LineFit upper, LineFit lower,
                                      double atr, double closeNow,
                                      double upperAtEnd, double lowerAtEnd,
                                      boolean confirmedBreak) {
        double c = 25.0;

        int touchesBeyondMin = Math.max(0, highs.size() - MIN_CONSOLIDATION_TOUCHES)
                + Math.max(0, lows.size() - MIN_CONSOLIDATION_TOUCHES);
        c += 5.0 * Math.min(3, touchesBeyondMin);

        double upperFitFrac = clamp01(1.0 - upper.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        double lowerFitFrac = clamp01(1.0 - lower.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        c += 5.0 * upperFitFrac;
        c += 5.0 * lowerFitFrac;

        // Approach to nearer line — continuous, [0, 15].
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

    private static Map<Instant, Integer> indexer(BarSeries series) {
        Map<Instant, Integer> m = new HashMap<>(series.getBarCount() * 2);
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            m.put(series.getBar(i).getEndTime(), i);
        }
        return m;
    }

    private record PivotRef(int idx, double price, PivotType type) {
        PivotRef(int idx, double price) { this(idx, price, null); }
    }

    private record LineFit(double slope, double intercept, double maxResidual) {
        double yAt(int idx) { return slope * idx + intercept; }
    }

    private record PoleRef(int startIdx, int endIdx, double startPrice, double endPrice,
                            double pctMove, boolean up) { }
}
