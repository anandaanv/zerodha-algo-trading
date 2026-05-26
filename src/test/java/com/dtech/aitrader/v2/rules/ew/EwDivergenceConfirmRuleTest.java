package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EwDivergenceConfirmRule — locks the Rule 0.95 bearish-divergence direction-by-form mechanic:
 * the same observation at B_end / W1_end CONFIRMS zigzag but CONTRADICTS impulse. Post-refactor
 * the rule reads {@code rsiAtPivot} directly from {@link MarketStructurePoint} (populated by
 * ScanContextParser from the per-pivot CSV column), so no BarSeries / IndicatorAccessor fixture
 * is needed.
 */
class EwDivergenceConfirmRuleTest {

    private static final EwDivergenceConfirmRule RULE = new EwDivergenceConfirmRule();

    @Test
    void bearish_divergence_at_B_end_confirms_zigzag() {
        Fixture fx = bearishDivergenceFixture();
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, fx.currentPrice, fx.currentDate);

        List<Firing> emitted = RULE.evaluate(fx.ctx, List.of(zz));
        assertEquals(1, emitted.size());
        Firing c = emitted.get(0);
        assertEquals(EwDivergenceConfirmRule.RULE_ID, c.getRuleId());
        assertEquals(Pass.P5_CONFIRMATION, c.getPass());
        assertEquals(FiresOn.CONFIRMATION, c.getFiresOn());

        Map<String, Object> p = c.getPayload();
        assertEquals("bearish", p.get("divergence_kind"));
        assertEquals("regular-bearish", p.get("divergence_type"));
        assertEquals("B_end", p.get("at_role"));
        assertEquals(Boolean.TRUE, p.get("confirms_framing"));
        assertEquals(PriorDelta.Kind.GRADUATED, c.getPriorDelta().kind());
        assertTrue(c.getPriorDelta().graduatedDelta() > 0.0,
                "bearish divergence at B_end must CONFIRM zigzag (positive delta)");
    }

    @Test
    void hidden_bearish_divergence_also_confirms_zigzag() {
        // Real RELIANCE Wk numbers: current 1473.4@RSI55.7 vs prior 1489.5@RSI48.0.
        // Price made LH but RSI made HH ⇒ hidden bearish.
        ZonedDateTime priorDt = LocalDate.of(2026, 1, 28).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime currentDt = LocalDate.of(2026, 4, 29).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        SymbolContext ctx = ctxWithPivots(List.of(
                pivot(priorDt, PivotType.HIGH, 1489.5, 48.0),
                pivot(currentDt, PivotType.HIGH, 1473.4, 55.7)));

        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, 1473.4,
                currentDt.toLocalDate().toString());
        List<Firing> emitted = RULE.evaluate(ctx, List.of(zz));
        assertEquals(1, emitted.size());
        Map<String, Object> p = emitted.get(0).getPayload();
        assertEquals("hidden-bearish", p.get("divergence_type"));
        assertEquals(Boolean.TRUE, p.get("confirms_framing"));
        assertTrue(emitted.get(0).getPriorDelta().graduatedDelta() > 0.0);
    }

    @Test
    void bearish_divergence_at_W1_end_contradicts_impulse() {
        Fixture fx = bearishDivergenceFixture();
        Firing imp = impulseCandidate("mf2", 1290.0, fx.currentPrice, fx.currentDate);

        List<Firing> emitted = RULE.evaluate(fx.ctx, List.of(imp));
        assertEquals(1, emitted.size());
        Firing c = emitted.get(0);

        Map<String, Object> p = c.getPayload();
        assertEquals("W1_end", p.get("at_role"));
        assertEquals(Boolean.FALSE, p.get("confirms_framing"));
        assertTrue(c.getPriorDelta().graduatedDelta() < 0.0,
                "bearish divergence at W1_end must CONTRADICT impulse (negative delta)");
    }

    @Test
    void no_divergence_no_firing() {
        // Two HIGH pivots at same price → no price-HH → no divergence.
        ZonedDateTime priorDt = LocalDate.of(2025, 1, 10).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime currentDt = LocalDate.of(2025, 2, 10).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        SymbolContext ctx = ctxWithPivots(List.of(
                pivot(priorDt, PivotType.HIGH, 1400.0, 70.0),
                pivot(currentDt, PivotType.HIGH, 1400.0, 60.0)));

        Firing zz = zigzagCandidate("zz", 1611.8, 1290.0, 1400.0,
                currentDt.toLocalDate().toString());
        assertTrue(RULE.evaluate(ctx, List.of(zz)).isEmpty(),
                "no price-HH ⇒ no divergence ⇒ no firing");
    }

    @Test
    void no_rsi_gap_no_firing() {
        // Price HH but RSI gap is below threshold (default 1.0pp) → no LH → no divergence.
        ZonedDateTime priorDt = LocalDate.of(2025, 1, 10).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime currentDt = LocalDate.of(2025, 2, 10).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        SymbolContext ctx = ctxWithPivots(List.of(
                pivot(priorDt, PivotType.HIGH, 1400.0, 70.0),
                pivot(currentDt, PivotType.HIGH, 1410.0, 69.5)));   // gap 0.5 < threshold 1.0

        Firing zz = zigzagCandidate("zz", 1611.8, 1290.0, 1410.0,
                currentDt.toLocalDate().toString());
        assertTrue(RULE.evaluate(ctx, List.of(zz)).isEmpty());
    }

    @Test
    void missing_prior_pivot_skips_silently() {
        ZonedDateTime currentDt = LocalDate.of(2025, 2, 10).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        SymbolContext ctx = ctxWithPivots(List.of(
                pivot(currentDt, PivotType.HIGH, 1473.4, 55.0)));   // only one — no prior

        Firing zz = zigzagCandidate("zz", 1611.8, 1290.0, 1473.4,
                currentDt.toLocalDate().toString());
        assertTrue(RULE.evaluate(ctx, List.of(zz)).isEmpty());
    }

    @Test
    void missing_rsi_on_pivot_skips_silently() {
        ZonedDateTime priorDt = LocalDate.of(2025, 1, 10).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime currentDt = LocalDate.of(2025, 2, 10).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        SymbolContext ctx = ctxWithPivots(List.of(
                pivot(priorDt, PivotType.HIGH, 1400.0, 70.0),
                pivot(currentDt, PivotType.HIGH, 1473.4, null)));   // current has no RSI

        Firing zz = zigzagCandidate("zz", 1611.8, 1290.0, 1473.4,
                currentDt.toLocalDate().toString());
        assertTrue(RULE.evaluate(ctx, List.of(zz)).isEmpty(),
                "missing rsiAtPivot must result in silent skip, not a misleading firing");
    }

    @Test
    void eliminated_candidates_skipped() {
        Fixture fx = bearishDivergenceFixture();
        Firing zz = zigzagCandidate("zz1", 1611.8, 1290.0, fx.currentPrice, fx.currentDate);
        Firing elim = Firing.builder()
                .id("e1").ruleId(EwHardRuleValidatorRule.RULE_ID).family(Family.EW)
                .pass(Pass.P3_VALIDATION).firesOn(FiresOn.ELIMINATION)
                .refs(List.of("zz1"))
                .priorDelta(PriorDelta.eliminate("test", "3"))
                .build();
        assertTrue(RULE.evaluate(fx.ctx, List.of(zz, elim)).isEmpty(),
                "eliminated candidates must be skipped");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private record Fixture(SymbolContext ctx, double priorPrice, double currentPrice,
                            String priorDate, String currentDate) {}

    /** Two Wk HIGH pivots: prior 1455 @ RSI 75, current (a HH) 1473 @ RSI 60 — clear bearish div. */
    private static Fixture bearishDivergenceFixture() {
        ZonedDateTime priorDt = LocalDate.of(2026, 2, 10).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime currentDt = LocalDate.of(2026, 4, 29).atStartOfDay(ZoneId.of("Asia/Kolkata"));
        double priorPrice = 1455.0;
        double currentPrice = 1473.4;
        SymbolContext ctx = ctxWithPivots(List.of(
                pivot(priorDt, PivotType.HIGH, priorPrice, 75.0),
                pivot(currentDt, PivotType.HIGH, currentPrice, 60.0)));
        return new Fixture(ctx, priorPrice, currentPrice,
                priorDt.toLocalDate().toString(), currentDt.toLocalDate().toString());
    }

    private static SymbolContext ctxWithPivots(List<MarketStructurePoint> pivots) {
        return SymbolContext.builder()
                .symbol("RELIANCE").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .pivots(pivots).build();
    }

    private static MarketStructurePoint pivot(ZonedDateTime dt, PivotType k, double price, Double rsi) {
        return MarketStructurePoint.builder()
                .pivotType(k)
                .structureLabel(StructureLabel.FIRST)
                .timestamp(dt.toInstant())
                .price(price)
                .atrAtPivot(10.0)
                .rsiAtPivot(rsi)
                .build();
    }

    private static Firing zigzagCandidate(String id, double aStart, double aEnd, double bEnd, String bEndDate) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        p.put("pivot_assignment", List.of(
                role("A_start", aStart, null),
                role("A_end", aEnd, null),
                role("B_end", bEnd, bEndDate)));
        return cand(id, p);
    }

    private static Firing impulseCandidate(String id, double w0, double w1End, String w1Date) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                role("W0", w0, null),
                role("W1_end", w1End, w1Date),
                role("W2_in_progress", null, null)));
        return cand(id, p);
    }

    private static Firing cand(String id, Map<String, Object> payload) {
        return Firing.builder()
                .id(id).ruleId(EwEnumerationRule.RULE_ID).family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.30).payload(payload).build();
    }

    private static Map<String, Object> role(String name, Double price, String date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", name);
        m.put("price", price);
        m.put("date", date);
        return m;
    }
}
