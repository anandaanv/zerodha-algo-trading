package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.SymbolContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EwHardRuleValidatorRule — locks Rule 3 inviolable checks. Includes the RELIANCE blessed-data
 * case (zigzag A+B completed but B does not exceed A_start → no elimination) and several
 * synthetic violation cases that MUST trigger CATEGORICAL_ELIMINATE.
 */
class EwHardRuleValidatorRuleTest {

    private static final EwHardRuleValidatorRule RULE = new EwHardRuleValidatorRule();

    @Test
    void reliance_blessed_zigzag_passes_rule3() {
        // MF1: A=1611.8→1290 (down), B=1290→1473.4 — B does not exceed A_start (1611.8). Pass.
        Firing mf1 = zigzag("mf1", 1611.8, 1290.0, 1473.4);
        List<Firing> emitted = RULE.evaluate(emptyCtx(), List.of(mf1));
        assertTrue(emitted.isEmpty(),
                "blessed MF1 zigzag must not violate Rule 3; got: " + emitted);
    }

    @Test
    void reliance_blessed_impulse_W0_W1_only_passes_rule3() {
        // MF2: W0=1290, W1_end=1473.4 — only two waves, no Rule-3 binding yet.
        Firing mf2 = impulseW0W1("mf2", 1290.0, 1473.4);
        assertTrue(RULE.evaluate(emptyCtx(), List.of(mf2)).isEmpty());
    }

    @Test
    void zigzag_B_exceeds_A_start_is_eliminated() {
        // A down: A_start=1600, A_end=1300. B_end=1700 exceeds A_start → eliminate.
        Firing bad = zigzag("bad-zz", 1600.0, 1300.0, 1700.0);
        List<Firing> emitted = RULE.evaluate(emptyCtx(), List.of(bad));
        assertEquals(1, emitted.size(), "B exceeds A_start must eliminate");
        Firing elim = emitted.get(0);
        assertEquals(FiresOn.ELIMINATION, elim.getFiresOn());
        assertEquals(Family.EW, elim.getFamily());
        assertEquals(Pass.P3_VALIDATION, elim.getPass());
        assertEquals(List.of("bad-zz"), elim.getRefs());
        assertEquals(PriorDelta.Kind.CATEGORICAL_ELIMINATE, elim.getPriorDelta().kind());
        assertEquals("3", elim.getPriorDelta().ruleRef());
    }

    @Test
    void impulse_W2_retrace_over_100pct_eliminated() {
        // Bullish impulse: W0=100, W1=150 (+50). W2_end=40 → retrace = 110 > 50 → eliminate.
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                roleP("W0", 100.0),
                roleP("W1_end", 150.0),
                roleP("W2_end", 40.0)));
        Firing bad = candidate("bad-w2", p);
        List<Firing> emitted = RULE.evaluate(emptyCtx(), List.of(bad));
        assertEquals(1, emitted.size());
        assertEquals(FiresOn.ELIMINATION, emitted.get(0).getFiresOn());
        assertTrue(emitted.get(0).getPayload().get("reason").toString().contains("W2 retrace"));
    }

    @Test
    void impulse_W4_overlaps_W1_territory_eliminated() {
        // Bullish impulse: W0=100, W1=150, W2=120, W3=200, W4=130 (within [100,150]) → eliminate.
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                roleP("W0", 100.0),
                roleP("W1_end", 150.0),
                roleP("W2_end", 120.0),
                roleP("W3_end", 200.0),
                roleP("W4_end", 130.0)));
        Firing bad = candidate("bad-w4", p);
        List<Firing> emitted = RULE.evaluate(emptyCtx(), List.of(bad));
        assertEquals(1, emitted.size());
        assertTrue(emitted.get(0).getPayload().get("reason").toString().contains("W4"));
    }

    @Test
    void impulse_W3_shortest_eliminated() {
        // W1=50, W3=20, W5=60 → W3 shortest of all three → eliminate (or diagonal re-frame).
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                roleP("W0", 100.0),
                roleP("W1_end", 150.0),     // W1 = 50
                roleP("W2_end", 130.0),
                roleP("W3_end", 150.0),     // W3 = 20
                roleP("W4_end", 145.0),
                roleP("W5_end", 205.0)));   // W5 = 60
        Firing bad = candidate("bad-w3", p);
        List<Firing> emitted = RULE.evaluate(emptyCtx(), List.of(bad));
        assertEquals(1, emitted.size());
        assertTrue(emitted.get(0).getPayload().get("reason").toString().contains("W3"));
    }

    @Test
    void no_candidates_no_emission() {
        assertTrue(RULE.evaluate(emptyCtx(), List.of()).isEmpty());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static SymbolContext emptyCtx() {
        return SymbolContext.builder()
                .symbol("TEST").tf("Week").asOf(LocalDate.of(2026, 5, 22)).build();
    }

    private static Firing zigzag(String id, double aStart, double aEnd, double bEnd) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        p.put("pivot_assignment", List.of(
                roleP("A_start", aStart),
                roleP("A_end", aEnd),
                roleP("B_end", bEnd)));
        return candidate(id, p);
    }

    private static Firing impulseW0W1(String id, double w0, double w1End) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                roleP("W0", w0),
                roleP("W1_end", w1End)));
        return candidate(id, p);
    }

    private static Firing candidate(String id, Map<String, Object> payload) {
        return Firing.builder()
                .id(id)
                .ruleId(EwEnumerationRule.RULE_ID)
                .pass(Pass.P2_ENUMERATION)
                .firesOn(FiresOn.CANDIDATE)
                .family(Family.EW)
                .basePrior(0.30)
                .payload(payload)
                .build();
    }

    private static Map<String, Object> roleP(String role, double price) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("price", price);
        return m;
    }
}
