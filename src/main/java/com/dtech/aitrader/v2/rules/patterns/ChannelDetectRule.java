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
 * Pass-2 candidate emitter for the CHANNEL family per SPEC-008 ({@code e332be7f}):
 * parallel sloped trendlines (slope-difference within tolerance) sloped in the same direction.
 *
 * <ul>
 *   <li><b>Up-channel</b>: both lines rising at similar slope; bias = LONG (continuation).
 *       Break BELOW lower line = channel-exit / bearish reversal signal.</li>
 *   <li><b>Down-channel</b>: both lines falling at similar slope; bias = SHORT (continuation).
 *       Break ABOVE upper line = channel-exit / bullish reversal signal.</li>
 * </ul>
 *
 * <p>Distinguishes from wedge (slopes diverge) by requiring slope-difference BELOW the wedge
 * gate, and from triangle (≥1 line flat) by requiring BOTH lines trending. Horizontal channel
 * (both flat) is the {@code RectangleDetectRule} domain — flat slopes get rejected here.
 *
 * <p>Per SPEC-008: forming on 2 touches each; established on 3rd touch; break = exit signal.
 * Bias on emit reflects the WITHIN-CHANNEL continuation direction; if the close has already
 * exited, {@code channel_state = "exited_*"} flags the direction.
 */
@Component
@Slf4j
public class ChannelDetectRule implements Rule {

    public static final String RULE_ID = "CHANNEL_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final int MIN_TOUCHES_PER_LINE = 2;
    private static final int MAX_CHANNEL_SPAN_BARS = 250;
    private static final int MIN_CHANNEL_SPAN_BARS = 6;
    /** Channel requires meaningful slope on both lines — pure flat is RectangleDetectRule's domain. */
    private static final double MIN_CHANNEL_SLOPE_PCT_PER_BAR = 0.0005;
    /** Parallelism gate: slope difference must be BELOW this for "channel" classification. */
    private static final double MAX_SLOPE_DIFF_PCT_PER_BAR = 0.0005;
    /** Channel must NOT converge meaningfully — that's wedge geometry. */
    private static final double MAX_CONVERGENCE_PCT = 0.20;
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
        if (spanBars < MIN_CHANNEL_SPAN_BARS || spanBars > MAX_CHANNEL_SPAN_BARS) return List.of();

        LineFit upperLine = fitLine(recentHighs);
        LineFit lowerLine = fitLine(recentLows);
        if (upperLine == null || lowerLine == null) return List.of();

        double upperMean = lineMeanPrice(recentHighs);
        double lowerMean = lineMeanPrice(recentLows);
        double upperSlopePct = upperMean > 0 ? upperLine.slope / upperMean : 0.0;
        double lowerSlopePct = lowerMean > 0 ? lowerLine.slope / lowerMean : 0.0;

        // Both lines must be trending same direction with sufficient slope.
        boolean bothRising = upperSlopePct >= MIN_CHANNEL_SLOPE_PCT_PER_BAR
                && lowerSlopePct >= MIN_CHANNEL_SLOPE_PCT_PER_BAR;
        boolean bothFalling = upperSlopePct <= -MIN_CHANNEL_SLOPE_PCT_PER_BAR
                && lowerSlopePct <= -MIN_CHANNEL_SLOPE_PCT_PER_BAR;
        if (!bothRising && !bothFalling) return List.of();

        // Parallelism gate.
        double slopeDiff = Math.abs(upperSlopePct - lowerSlopePct);
        if (slopeDiff > MAX_SLOPE_DIFF_PCT_PER_BAR) return List.of();

        // Reject if it's actually converging (would be a wedge).
        double heightAtStart = upperLine.yAt(spanStart) - lowerLine.yAt(spanStart);
        double heightAtSpanEnd = upperLine.yAt(spanEnd) - lowerLine.yAt(spanEnd);
        if (heightAtStart <= 0 || heightAtSpanEnd <= 0) return List.of();
        double convergenceFrac = 1.0 - heightAtSpanEnd / heightAtStart;
        if (Math.abs(convergenceFrac) > MAX_CONVERGENCE_PCT) return List.of();

        String channelDirection = bothRising ? "up" : "down";
        String bias = bothRising ? "LONG" : "SHORT";

        double upperAtEnd = upperLine.yAt(endIdx);
        double lowerAtEnd = lowerLine.yAt(endIdx);
        // Channel "state" — inside / exited above / exited below.
        String channelState;
        boolean exited = false;
        if (closeNow > upperAtEnd) {
            channelState = "exited_above";
            exited = true;
            if (bothFalling) bias = "LONG";   // exit above a down-channel = reversal
        } else if (closeNow < lowerAtEnd) {
            channelState = "exited_below";
            exited = true;
            if (bothRising) bias = "SHORT";   // exit below an up-channel = reversal
        } else {
            channelState = "inside";
        }

        int totalTouches = recentHighs.size() + recentLows.size();
        // Per spec: 2 touches each → forming; 3+ touches per line OR exit break → confirmed.
        boolean meetsConfirmedTouches = recentHighs.size() >= 3 && recentLows.size() >= 3;
        boolean confirmedBreak = exited || meetsConfirmedTouches;

        double completion = computeCompletion(recentHighs, recentLows, upperLine, lowerLine,
                slopeDiff, convergenceFrac, atr, closeNow, upperAtEnd, lowerAtEnd, confirmedBreak);
        if (completion < EMISSION_THRESHOLD) return List.of();

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
        payload.put("upper_line_at_now", upperAtEnd);
        payload.put("lower_line_at_now", lowerAtEnd);
        payload.put("convergence_pct", convergenceFrac);
        payload.put("upper_touches", recentHighs.size());
        payload.put("lower_touches", recentLows.size());
        payload.put("upper_fit_residual_atr", upperLine.maxResidual / atr);
        payload.put("lower_fit_residual_atr", lowerLine.maxResidual / atr);
        payload.put("span_bars", spanBars);
        // Trigger / target / invalidation depend on whether we're in-channel or post-exit.
        if ("inside".equals(channelState)) {
            // Trigger = next break direction depends on bias direction (continuation: hit far line)
            payload.put("trigger_price", bothRising ? upperAtEnd : lowerAtEnd);
            payload.put("invalidation_price", bothRising ? lowerAtEnd : upperAtEnd);
            payload.put("target_price", bothRising ? upperAtEnd + 0.5 * heightAtStart
                    : lowerAtEnd - 0.5 * heightAtStart);
        } else if ("exited_above".equals(channelState)) {
            payload.put("trigger_price", upperAtEnd);
            payload.put("invalidation_price", lowerAtEnd);
            payload.put("target_price", upperAtEnd + heightAtStart);
        } else { // exited_below
            payload.put("trigger_price", lowerAtEnd);
            payload.put("invalidation_price", upperAtEnd);
            payload.put("target_price", lowerAtEnd - heightAtStart);
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
                                      double slopeDiff, double convergenceFrac,
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

        // Parallelism quality — tighter slope match = higher.
        double parallelismFrac = clamp01(1.0 - slopeDiff / MAX_SLOPE_DIFF_PCT_PER_BAR);
        c += 10.0 * parallelismFrac;

        // Channel-width stability — |convergence| close to 0 = perfect channel.
        double stabilityFrac = clamp01(1.0 - Math.abs(convergenceFrac) / MAX_CONVERGENCE_PCT);
        c += 10.0 * stabilityFrac;

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
