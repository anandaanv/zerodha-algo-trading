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

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EwPreConclusionGateRule — locks the price-confirmation gate per SPEC reframe ({@code 159ba913}).
 *
 * <p>Gate emits CONFIRMED iff current price has broken the candidate's reference (counter LOW
 * for downside zigzag SHORT; swing HIGH for bullish impulse LONG). UNCONFIRMED otherwise. The
 * "current price" is the latest-timestamped pivot across all TFs (Hr preferred, Day/Wk fallback).
 */
class EwPreConclusionGateRuleTest {

    private final EwPreConclusionGateRule rule = new EwPreConclusionGateRule();

    @Test
    void downside_zigzag_unconfirmed_when_price_above_counter() {
        // Blessed RELIANCE-style: counter = A_end = 1290.0; current price 1318.7 > 1290.0 → SHORT
        // thesis NOT confirmed. The gate must say UNCONFIRMED.
        Firing zz = zigzagCandidate("zz1", 1611.8, 1290.0, 1473.4);
        SymbolContext ctx = ctxWithLatestPivot(1318.7);

        List<Firing> emitted = rule.evaluate(ctx, List.of(zz));
        assertEquals(1, emitted.size());
        Map<String, Object> p = emitted.get(0).getPayload();
        assertEquals("UNCONFIRMED", p.get("gate_state"));
        assertEquals("SHORT", p.get("thesis_direction"));
        assertEquals("A_end", p.get("reference_role"));
        assertEquals(1290.0, ((Number) p.get("reference_level")).doubleValue(), 1e-6);
        assertEquals(1318.7, ((Number) p.get("current_price")).doubleValue(), 1e-6);
        assertEquals(1290.0, ((Number) p.get("awaiting_break_of")).doubleValue(), 1e-6);
    }

    @Test
    void downside_zigzag_confirmed_when_price_below_counter() {
        // TCS-style: counter 2346.2, current 2212.1 < 2346.2 → SHORT thesis price-confirmed.
        Firing zz = zigzagCandidate("zz1", 4592.25, 2346.2, 2614.0);
        SymbolContext ctx = ctxWithLatestPivot(2212.1);

        Firing gate = rule.evaluate(ctx, List.of(zz)).get(0);
        assertEquals("CONFIRMED", gate.getPayload().get("gate_state"));
        assertNull(gate.getPayload().get("awaiting_break_of"),
                "CONFIRMED gates clear the awaiting-break-of field");
    }

    @Test
    void bullish_impulse_unconfirmed_when_price_below_W1_end() {
        // Impulse LONG confirms only when price breaks ABOVE W1_end. Latest 1318.7 < 1473.4 →
        // UNCONFIRMED.
        Firing imp = impulseCandidate("imp1", 1290.0, 1473.4);
        SymbolContext ctx = ctxWithLatestPivot(1318.7);

        Firing gate = rule.evaluate(ctx, List.of(imp)).get(0);
        assertEquals("UNCONFIRMED", gate.getPayload().get("gate_state"));
        assertEquals("LONG", gate.getPayload().get("thesis_direction"));
        assertEquals("W1_end", gate.getPayload().get("reference_role"));
        assertEquals("above", gate.getPayload().get("break_direction"));
    }

    @Test
    void bullish_impulse_confirmed_when_price_above_W1_end() {
        Firing imp = impulseCandidate("imp1", 1290.0, 1473.4);
        SymbolContext ctx = ctxWithLatestPivot(1500.0);

        Firing gate = rule.evaluate(ctx, List.of(imp)).get(0);
        assertEquals("CONFIRMED", gate.getPayload().get("gate_state"));
    }

    @Test
    void no_pivots_no_firings() {
        Firing zz = zigzagCandidate("zz1", 1611.8, 1290.0, 1473.4);
        SymbolContext ctx = SymbolContext.builder().symbol("X").tf("Week")
                .asOf(LocalDate.of(2026, 5, 25)).build();
        assertTrue(rule.evaluate(ctx, List.of(zz)).isEmpty(),
                "no pivots ⇒ cannot compute current price ⇒ skip (no firing)");
    }

    @Test
    void eliminated_candidates_skipped() {
        Firing zz = zigzagCandidate("zz1", 1611.8, 1290.0, 1473.4);
        Firing elim = Firing.builder()
                .id("e1").ruleId("EW_HARD_RULE_VALIDATOR").family(Family.EW)
                .pass(Pass.P3_VALIDATION).firesOn(FiresOn.ELIMINATION)
                .refs(List.of("zz1"))
                .priorDelta(PriorDelta.eliminate("test", "3"))
                .build();
        SymbolContext ctx = ctxWithLatestPivot(1300.0);

        assertTrue(rule.evaluate(ctx, List.of(zz, elim)).isEmpty(),
                "eliminated candidates must not be gated");
    }

    @Test
    void picks_most_recent_pivot_across_TFs() {
        // Wk pivot @ 2026-01-01 vs Hr pivot @ 2026-04-29 — Hr is more recent, must be picked.
        Firing zz = zigzagCandidate("zz1", 1611.8, 1290.0, 1473.4);
        Map<String, List<MarketStructurePoint>> byTf = new LinkedHashMap<>();
        byTf.put("Week", List.of(pivotAt("2026-01-01T00:00:00Z", 1611.8, PivotType.HIGH)));
        byTf.put("OneHour", List.of(pivotAt("2026-04-29T09:45:00Z", 1318.7, PivotType.LOW)));
        SymbolContext ctx = SymbolContext.builder().symbol("RELIANCE").tf("Week")
                .asOf(LocalDate.of(2026, 5, 25)).pivotsByTf(byTf).build();

        Firing gate = rule.evaluate(ctx, List.of(zz)).get(0);
        assertEquals(1318.7, ((Number) gate.getPayload().get("current_price")).doubleValue(), 1e-6,
                "most-recent pivot (Hr 2026-04-29) wins, not the earlier Wk pivot");
        assertTrue(gate.getPayload().get("current_price_source").toString().contains("OneHour"));
    }

    @Test
    void zero_delta_no_ranking_side_effect() {
        // SPEC reframe: validity is not a weight. Gate firings must NOT contribute to prior-fold
        // ranking; their prior_delta must be GRADUATED with magnitude 0.0 (audit only).
        Firing zz = zigzagCandidate("zz1", 1611.8, 1290.0, 1473.4);
        SymbolContext ctx = ctxWithLatestPivot(1318.7);
        Firing gate = rule.evaluate(ctx, List.of(zz)).get(0);
        assertEquals(PriorDelta.Kind.GRADUATED, gate.getPriorDelta().kind());
        assertEquals(0.0, gate.getPriorDelta().graduatedDelta(), 1e-9,
                "gate firings must have zero prior-delta — they are decision inputs, not weights");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static SymbolContext ctxWithLatestPivot(double price) {
        Map<String, List<MarketStructurePoint>> byTf = new LinkedHashMap<>();
        byTf.put("OneHour", List.of(pivotAt("2026-04-29T09:45:00Z", price, PivotType.LOW)));
        return SymbolContext.builder().symbol("TEST").tf("Week")
                .asOf(LocalDate.of(2026, 5, 25)).pivotsByTf(byTf).build();
    }

    private static MarketStructurePoint pivotAt(String iso, double price, PivotType k) {
        return MarketStructurePoint.builder()
                .pivotType(k).structureLabel(StructureLabel.FIRST)
                .timestamp(Instant.parse(iso)).price(price)
                .atrAtPivot(10.0).rsiAtPivot(50.0).build();
    }

    private static Firing zigzagCandidate(String id, double aStart, double aEnd, double bEnd) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        p.put("pivot_assignment", List.of(
                role("A_start", aStart),
                role("A_end", aEnd),
                role("B_end", bEnd)));
        return Firing.builder()
                .id(id).ruleId("EW_ENUMERATION").family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.45).payload(p).build();
    }

    private static Firing impulseCandidate(String id, double w0, double w1End) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                role("W0", w0),
                role("W1_end", w1End)));
        return Firing.builder()
                .id(id).ruleId("EW_ENUMERATION").family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.25).payload(p).build();
    }

    private static Map<String, Object> role(String name, double price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", name);
        m.put("price", price);
        m.put("date", null);
        return m;
    }
}
