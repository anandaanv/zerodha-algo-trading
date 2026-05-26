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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-2 candidate emitter for the bearish double-top pattern with FORMING/early-sign detection
 * per owner correction ({@code 89a52589}).
 *
 * <p>Owner forming criterion: "price makes ONE pivot against the trend AND takes
 * support/resistance near the previous pivot." Translated: second HIGH lands near the first
 * HIGH (within ATR tolerance), forming the second touch of the resistance level. Completion
 * grows continuously as the second touch becomes more established (level proximity, rejection
 * depth, distance-to-neckline) and snaps to confirmed on neckline breakdown.
 *
 * <p>Completion formula (continuous [0, 100]):
 * <pre>
 *   backbone (2 highs + intervening low + ordering + separation) : +30
 *   level proximity (how close p2 is to p1, within ATR tol)      : +[0, 20]
 *   rejection from p2 (close depth below p2)                     : +[0, 20]
 *   neckline approach (close close to but above neckline)        : +[0, 15]
 *   confirmed breakdown (close strictly below neckline)          : clamps to ≥85, +15 → 100
 * </pre>
 * Emission threshold: 25. Forming firings (25 ≤ completion &lt; 95) carry status="forming";
 * neckline-break firings carry status="confirmed". Cross-family consumers (EW confluence) filter
 * by status until Q8 ({@code 5a2a7efa}) opens the completion_pct → tilt scaling.
 */
@Component
@Slf4j
public class DoubleTopDetectRule implements Rule {

    public static final String RULE_ID = "DOUBLE_TOP_DETECT";

    private static final int ATR_PERIOD = 14;
    private static final double EQUAL_HIGH_TOLERANCE_ATR = 1.5;
    private static final int MIN_SEPARATION_BARS = 5;
    private static final int MAX_SEPARATION_BARS = 40;
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
        if (series == null || pivots == null || pivots.size() < 3) return List.of();

        int endIdx = series.getEndIndex();
        if (endIdx < 1) return List.of();

        ATRIndicator atr = new ATRIndicator(series, ATR_PERIOD);
        double atrNow = atr.getValue(endIdx).doubleValue();
        if (atrNow <= 0) return List.of();

        // Most-recent two HIGH pivots.
        MarketStructurePoint p2 = null, p1 = null;
        for (int i = pivots.size() - 1; i >= 0 && p1 == null; i--) {
            MarketStructurePoint p = pivots.get(i);
            if (p.getPivotType() != PivotType.HIGH) continue;
            if (p2 == null) p2 = p; else p1 = p;
        }
        if (p1 == null || p2 == null) return List.of();

        Map<java.time.Instant, Integer> indexer = indexer(series);
        Integer i1 = indexer.get(p1.getTimestamp());
        Integer i2 = indexer.get(p2.getTimestamp());
        if (i1 == null || i2 == null || i2 <= i1) return List.of();

        int separation = i2 - i1;
        if (separation < MIN_SEPARATION_BARS || separation > MAX_SEPARATION_BARS) return List.of();

        // Level proximity gate — must be within ATR tolerance to even be a double-top candidate.
        // Without this gate, every two-HIGHs sequence would qualify as forming.
        double priceDelta = Math.abs(p1.getPrice() - p2.getPrice());
        if (priceDelta > EQUAL_HIGH_TOLERANCE_ATR * atrNow) return List.of();

        // Intervening LOW (neckline).
        MarketStructurePoint neckline = null;
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() != PivotType.LOW) continue;
            Integer idx = indexer.get(p.getTimestamp());
            if (idx == null) continue;
            if (idx <= i1 || idx >= i2) continue;
            if (neckline == null || p.getPrice() < neckline.getPrice()) neckline = p;
        }
        if (neckline == null) return List.of();

        double necklinePrice = neckline.getPrice();
        double closeNow = series.getBar(endIdx).getClosePrice().doubleValue();
        double closePrev = series.getBar(endIdx - 1).getClosePrice().doubleValue();

        boolean broke = closeNow < necklinePrice && closePrev >= necklinePrice;
        boolean alreadyBelowNeckline = closeNow < necklinePrice;
        boolean confirmedBreak = broke || alreadyBelowNeckline;

        double completion = computeCompletion(p1.getPrice(), p2.getPrice(), necklinePrice,
                closeNow, atrNow, confirmedBreak);
        if (completion < EMISSION_THRESHOLD) return List.of();

        String status = completion >= CONFIRMED_THRESHOLD ? "confirmed" : "forming";

        double highMax = Math.max(p1.getPrice(), p2.getPrice());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status);
        payload.put("completion_pct", completion);
        payload.put("p1_idx", i1);
        payload.put("p1_price", p1.getPrice());
        payload.put("p2_idx", i2);
        payload.put("p2_price", p2.getPrice());
        payload.put("neckline_idx", indexer.get(neckline.getTimestamp()));
        payload.put("neckline_price", necklinePrice);
        payload.put("high_max", highMax);
        payload.put("atr_at_breakdown", atrNow);
        payload.put("trigger_price", necklinePrice);
        payload.put("invalidation_price", highMax + 0.5 * atrNow);
        payload.put("target_price", necklinePrice - (highMax - necklinePrice));
        payload.put("bias", "SHORT");
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
     * Continuous completion in [0, 100]. The level-proximity gate is enforced before this is
     * called (so any return ≥30 reflects a structurally-valid candidate).
     */
    private static double computeCompletion(double p1Price, double p2Price, double necklinePrice,
                                              double closeNow, double atr, boolean confirmedBreak) {
        double c = 30.0;   // backbone established (caller validated)

        // Level proximity: how close p2 is to p1 (already within ATR tol; reward symmetry).
        double levelErr = Math.abs(p1Price - p2Price) / Math.max(1e-9, atr * EQUAL_HIGH_TOLERANCE_ATR);
        c += 20.0 * clamp01(1.0 - levelErr);

        // Rejection: close descending from p2 toward neckline.
        if (closeNow < p2Price) {
            double rejectionRange = Math.max(1e-9, p2Price - necklinePrice);
            double rejectionFrac = clamp01((p2Price - closeNow) / rejectionRange);
            c += 20.0 * rejectionFrac;
        }

        // Neckline approach: close close to but above neckline (pre-break tension).
        if (closeNow > necklinePrice) {
            double approachRange = Math.max(1e-9, p2Price - necklinePrice);
            double approachFrac = clamp01(1.0 - (closeNow - necklinePrice) / approachRange);
            c += 15.0 * approachFrac;
        }

        if (confirmedBreak) {
            c = Math.max(c, 85.0) + 15.0;
        }
        return Math.min(100.0, c);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    private static Map<java.time.Instant, Integer> indexer(BarSeries series) {
        Map<java.time.Instant, Integer> m = new HashMap<>(series.getBarCount() * 2);
        for (int i = series.getBeginIndex(); i <= series.getEndIndex(); i++) {
            m.put(series.getBar(i).getEndTime(), i);
        }
        return m;
    }
}
