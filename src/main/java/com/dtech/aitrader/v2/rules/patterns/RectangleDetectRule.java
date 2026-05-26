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
 * Pass-2 candidate emitter for the RECTANGLE / RANGE family per SPEC-008 ({@code e332be7f}):
 * both upper and lower trendlines are essentially flat (|slope| below the flat gate), and the
 * range height is meaningful (≥ {@link #MIN_RECT_HEIGHT_ATR} ATR).
 *
 * <p>Bias = NEUTRAL inside the range; LONG on close above the upper line, SHORT on close below
 * the lower line. Confirmation also catches the already-broken case (close already beyond
 * either edge on prior bars).
 *
 * <p>Distinguishes from triangle (≥1 line sloped), channel (both sloped same direction) and
 * wedge (both sloped + converging) by requiring BOTH lines flat.
 */
@Component
@Slf4j
public class RectangleDetectRule implements Rule {

    public static final String RULE_ID = "RECTANGLE_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final int MIN_TOUCHES_PER_LINE = 2;
    private static final int MAX_RECT_SPAN_BARS = 250;
    private static final int MIN_RECT_SPAN_BARS = 6;
    /** Flat gate — both lines' |slope| must be BELOW this for "rectangle" classification. */
    private static final double FLAT_SLOPE_PCT_PER_BAR = 0.0008;
    /** Rectangle range height must be at least this many ATR to be a tradeable range. */
    private static final double MIN_RECT_HEIGHT_ATR = 1.5;
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
        if (spanBars < MIN_RECT_SPAN_BARS || spanBars > MAX_RECT_SPAN_BARS) return List.of();

        LineFit upperLine = fitLine(recentHighs);
        LineFit lowerLine = fitLine(recentLows);
        if (upperLine == null || lowerLine == null) return List.of();

        double upperMean = lineMeanPrice(recentHighs);
        double lowerMean = lineMeanPrice(recentLows);
        double upperSlopePct = upperMean > 0 ? upperLine.slope / upperMean : 0.0;
        double lowerSlopePct = lowerMean > 0 ? lowerLine.slope / lowerMean : 0.0;

        // BOTH lines must be flat.
        boolean upperFlat = Math.abs(upperSlopePct) < FLAT_SLOPE_PCT_PER_BAR;
        boolean lowerFlat = Math.abs(lowerSlopePct) < FLAT_SLOPE_PCT_PER_BAR;
        if (!upperFlat || !lowerFlat) return List.of();

        double upperAtEnd = upperLine.yAt(endIdx);
        double lowerAtEnd = lowerLine.yAt(endIdx);
        double rectHeight = upperAtEnd - lowerAtEnd;
        if (rectHeight <= 0) return List.of();
        // Must be a meaningful tradeable range.
        if (rectHeight < MIN_RECT_HEIGHT_ATR * atr) return List.of();

        // Range state — inside / broken above / broken below.
        String rangeState;
        String bias;
        boolean broken = false;
        boolean brokenUp = closeNow > upperAtEnd;
        boolean brokenDown = closeNow < lowerAtEnd;
        boolean alreadyAbove = closePrev > upperAtEnd && brokenUp;
        boolean alreadyBelow = closePrev < lowerAtEnd && brokenDown;
        if (brokenUp) {
            rangeState = "broken_up";
            bias = "LONG";
            broken = true;
        } else if (brokenDown) {
            rangeState = "broken_down";
            bias = "SHORT";
            broken = true;
        } else {
            rangeState = "inside";
            bias = "NEUTRAL";
        }

        boolean confirmedBreak = broken || alreadyAbove || alreadyBelow;

        double completion = computeCompletion(recentHighs, recentLows, upperLine, lowerLine,
                upperSlopePct, lowerSlopePct, atr, closeNow, upperAtEnd, lowerAtEnd, confirmedBreak);
        if (completion < EMISSION_THRESHOLD) return List.of();

        String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("range_state", rangeState);
        payload.put("bias", bias);
        payload.put("upper_line_at_now", upperAtEnd);
        payload.put("lower_line_at_now", lowerAtEnd);
        payload.put("rect_height", rectHeight);
        payload.put("rect_height_atr", rectHeight / atr);
        payload.put("upper_slope_pct_per_bar", upperSlopePct);
        payload.put("lower_slope_pct_per_bar", lowerSlopePct);
        payload.put("upper_touches", recentHighs.size());
        payload.put("lower_touches", recentLows.size());
        payload.put("upper_fit_residual_atr", upperLine.maxResidual / atr);
        payload.put("lower_fit_residual_atr", lowerLine.maxResidual / atr);
        payload.put("span_bars", spanBars);
        // Trigger/target/invalidation depend on state.
        if ("inside".equals(rangeState)) {
            payload.put("trigger_price", upperAtEnd);   // upper break is the canonical trigger
            payload.put("invalidation_price", lowerAtEnd);
            payload.put("target_price", upperAtEnd + rectHeight); // measured move on break-up
        } else if ("broken_up".equals(rangeState)) {
            payload.put("trigger_price", upperAtEnd);
            payload.put("invalidation_price", lowerAtEnd);
            payload.put("target_price", upperAtEnd + rectHeight);
        } else { // broken_down
            payload.put("trigger_price", lowerAtEnd);
            payload.put("invalidation_price", upperAtEnd);
            payload.put("target_price", lowerAtEnd - rectHeight);
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

    private double computeCompletion(List<PivotRef> highs, List<PivotRef> lows,
                                      LineFit upper, LineFit lower,
                                      double upperSlopePct, double lowerSlopePct,
                                      double atr, double closeNow,
                                      double upperAtEnd, double lowerAtEnd,
                                      boolean confirmedBreak) {
        double c = 25.0;

        int touchesBeyondMin = Math.max(0, highs.size() - MIN_TOUCHES_PER_LINE)
                + Math.max(0, lows.size() - MIN_TOUCHES_PER_LINE);
        c += 5.0 * Math.min(3, touchesBeyondMin);

        double upperFitFrac = clamp01(1.0 - upper.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        double lowerFitFrac = clamp01(1.0 - lower.maxResidual / (atr * LINE_FIT_RESIDUAL_ATR));
        c += 5.0 * upperFitFrac;
        c += 5.0 * lowerFitFrac;

        // Flatness quality — closer to zero slope on BOTH lines = higher rectangle quality.
        double upperFlatFrac = clamp01(1.0 - Math.abs(upperSlopePct) / FLAT_SLOPE_PCT_PER_BAR);
        double lowerFlatFrac = clamp01(1.0 - Math.abs(lowerSlopePct) / FLAT_SLOPE_PCT_PER_BAR);
        c += 7.5 * upperFlatFrac;
        c += 7.5 * lowerFlatFrac;

        // Approach to nearer edge — continuous, [0, 15].
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
