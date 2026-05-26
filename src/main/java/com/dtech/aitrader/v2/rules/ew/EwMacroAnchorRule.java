package com.dtech.aitrader.v2.rules.ew;

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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pass-1 EW macro-anchor selector. Per canonical EW Rule 0 (macro→micro→nano) the EW analysis
 * must anchor on the largest-degree current structure on the highest TF available (Wk).
 *
 * <p>Deterministic algorithm (matches blessed RELIANCE reference {@code cde6bbc9}: anchor=1611.8 @
 * 2025-12-31, role=corrective):
 * <ol>
 *   <li>Require ≥6 pivots (else emit a {@code data_sufficient=false} FACT and stop).</li>
 *   <li>Find {@code H} = HIGH pivot with maximum price across the series.</li>
 *   <li>Find {@code L} = LOW pivot with minimum price across the series.</li>
 *   <li>Anchor = whichever came LATER chronologically (the current structure is whatever started
 *       at that most-recent absolute extreme).</li>
 *   <li>Role = CORRECTIVE if anchor is the HIGH (price descended after); IMPULSIVE if anchor is
 *       the LOW (price rallied after).</li>
 *   <li>Magnitude = {@code |H.price - L.price|}; magnitude_pct = magnitude / max(H, L) × 100.</li>
 * </ol>
 *
 * <p>Why "later of the absolute extremes" matches the blessed reference: at the asOf, the dominant
 * recent move is whatever started at the most-recent extreme. For RELIANCE the absolute HIGH
 * (1611.8 @ 2025-12-31) came AFTER the absolute LOW (1200 @ 2022-08-15), so the current structure
 * started at 1611.8 and is descending — corrective. The same algorithm picks an impulsive-from-LOW
 * anchor in markets where the most-recent absolute extreme is a low.
 *
 * <p>This rule consumes {@code ctx.getPivots()} which the loader populates with the TF the engine
 * was invoked on. For EW runs that should be Wk pivots — caller's responsibility.
 */
@Component
@Slf4j
public class EwMacroAnchorRule implements Rule {

    public static final String RULE_ID = "EW_MACRO_ANCHOR";

    /** Canonical Rule-0 minimum: don't anchor unless we have meaningful weekly history. */
    private static final int MIN_PIVOTS = 6;

    @Override public String ruleId() { return RULE_ID; }
    @Override public Pass pass() { return Pass.P1_STRUCTURAL; }
    @Override public Family family() { return Family.EW; }

    @Override
    public List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings) {
        // Prefer Wk pivots from pivotsByTf (real scan-context path); fall back to single-TF
        // context.pivots (unit-test path).
        List<MarketStructurePoint> pivots = null;
        if (ctx.getPivotsByTf() != null && ctx.getPivotsByTf().containsKey("Week")) {
            pivots = ctx.getPivotsByTf().get("Week");
        }
        if (pivots == null) pivots = ctx.getPivots();
        if (pivots == null || pivots.size() < MIN_PIVOTS) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("data_sufficient", Boolean.FALSE);
            payload.put("pivot_count", pivots == null ? 0 : pivots.size());
            payload.put("required", MIN_PIVOTS);
            return List.of(buildFact(ctx, payload));
        }

        MarketStructurePoint highest = null;
        MarketStructurePoint lowest = null;
        for (MarketStructurePoint p : pivots) {
            if (p.getPivotType() == PivotType.HIGH) {
                if (highest == null || p.getPrice() > highest.getPrice()) highest = p;
            } else if (p.getPivotType() == PivotType.LOW) {
                if (lowest == null || p.getPrice() < lowest.getPrice()) lowest = p;
            }
        }

        if (highest == null || lowest == null) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("data_sufficient", Boolean.FALSE);
            payload.put("reason", "missing extreme — need both HIGH and LOW pivots");
            return List.of(buildFact(ctx, payload));
        }

        // Anchor on the more recent of the two absolute extremes — the start of the current structure.
        boolean highIsMoreRecent = highest.getTimestamp().isAfter(lowest.getTimestamp());
        MarketStructurePoint anchor = highIsMoreRecent ? highest : lowest;
        boolean corrective = highIsMoreRecent;  // price descended from the most-recent HIGH

        // Counter-extreme = the most extreme opposite-type pivot that came AFTER the anchor.
        // Anchored magnitude = |anchor.price - counterExtreme.price|, NOT the series-wide span.
        // (Blessed RELIANCE: anchor=1611.8 → counter=1290 (subsequent LL, not the 2022 1200) → 321.8.)
        MarketStructurePoint counterExtreme = null;
        for (MarketStructurePoint p : pivots) {
            if (!p.getTimestamp().isAfter(anchor.getTimestamp())) continue;
            if (corrective && p.getPivotType() == PivotType.LOW) {
                if (counterExtreme == null || p.getPrice() < counterExtreme.getPrice()) counterExtreme = p;
            } else if (!corrective && p.getPivotType() == PivotType.HIGH) {
                if (counterExtreme == null || p.getPrice() > counterExtreme.getPrice()) counterExtreme = p;
            }
        }
        // If no counter-extreme exists yet (anchor IS the most recent pivot), use the opposite-type
        // pre-anchor extreme as fallback so magnitude is still defined.
        if (counterExtreme == null) {
            counterExtreme = corrective ? lowest : highest;
        }
        double magnitude = Math.abs(anchor.getPrice() - counterExtreme.getPrice());
        double magnitudePct = magnitude / Math.max(anchor.getPrice(), 1e-9) * 100.0;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data_sufficient", Boolean.TRUE);
        payload.put("anchor_price", anchor.getPrice());
        payload.put("anchor_date", toIstDate(anchor.getTimestamp()));
        payload.put("anchor_kind", anchor.getPivotType().name());
        payload.put("anchor_structure_label",
                anchor.getStructureLabel() != null ? anchor.getStructureLabel().name() : null);
        payload.put("counter_extreme_price", counterExtreme.getPrice());
        payload.put("counter_extreme_date", toIstDate(counterExtreme.getTimestamp()));
        payload.put("magnitude_pts", magnitude);
        payload.put("magnitude_pct", magnitudePct);
        payload.put("role_candidate", corrective ? "corrective" : "impulsive");
        payload.put("pivot_count", pivots.size());

        return List.of(buildFact(ctx, payload));
    }

    private Firing buildFact(SymbolContext ctx, Map<String, Object> payload) {
        return Firing.builder()
                .ruleId(RULE_ID)
                .symbol(ctx.getSymbol())
                .tf(ctx.getTf())
                .asOf(ctx.getAsOf())
                .family(Family.EW)
                .pass(Pass.P1_STRUCTURAL)
                .firesOn(FiresOn.FACT)
                .roundNum(1)
                .payload(payload)
                .context(ctx.getProbe())
                .build();
    }

    private static String toIstDate(Instant t) {
        if (t == null) return null;
        return LocalDate.ofInstant(t, ZoneId.of("Asia/Kolkata")).toString();
    }
}
