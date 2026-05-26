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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EwMagnitudeRule — locks the LOAD-BEARING Rule-0.65 classifications against blessed RELIANCE
 * reference {@code cde6bbc9}:
 *
 * <ul>
 *   <li>MF1 zigzag: B/A = 183/322 = 57% → zigzag-B verified (38-78%)</li>
 *   <li>MF2 impulse W2 in progress: (1473.4 − 1318.7) / (1473.4 − 1290) = 84.4% → atypical (78-90%)</li>
 * </ul>
 */
class EwMagnitudeRuleTest {

    private static final EwMagnitudeRule RULE = new EwMagnitudeRule();

    @Test
    void mf1_zigzag_B_57pct_verified() {
        // Blessed: A = 1611.8→1290.0 (322 pts), B = 1290→1473.4 (183 pts). B/A = 57%.
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, 1473.4);
        SymbolContext ctx = blankCtx();

        List<Firing> emitted = RULE.evaluate(ctx, List.of(zz));
        assertEquals(1, emitted.size());
        Firing c = emitted.get(0);
        assertEquals(EwMagnitudeRule.RULE_ID, c.getRuleId());
        assertEquals(Pass.P4_CLASSIFICATION, c.getPass());
        assertEquals(FiresOn.CLASSIFICATION, c.getFiresOn());
        assertEquals(Family.EW, c.getFamily());
        assertEquals(List.of("mf1"), c.getRefs());

        Map<String, Object> p = c.getPayload();
        // The load-bearing number — 57% within 0.5 percentage points.
        assertEquals(57.0, (double) p.get("b_over_a_pct"), 0.5,
                "B/A must classify as 57% per blessed reference");
        assertEquals("zigzag-B-verified", p.get("classification"));
        assertEquals("0.65", p.get("rule_ref"));

        // Verified zigzag-B = no demote (basePrior stands).
        assertEquals(PriorDelta.Kind.GRADUATED, c.getPriorDelta().kind());
        assertEquals(0.0, c.getPriorDelta().graduatedDelta(), 1e-9,
                "verified zigzag-B should NOT demote — MF1 stays at basePrior 0.45");
    }

    @Test
    void mf2_impulse_W2_in_progress_85pct_atypical() {
        // Blessed: W0 = 1290, W1_end = 1473.4, current Hr low = 1318.7. W2 = 154.7 / 183.4 = 84.3%.
        Firing imp = impulseCandidateW0W1("mf2", 1290.0, 1473.4, "2026-04-29");

        // Build pivotsByTf with an Hr LOW at 1318.7 after W1_end.
        Map<String, List<MarketStructurePoint>> byTf = new LinkedHashMap<>();
        byTf.put("OneHour", List.of(
                hrPivot(2026, 5,  5, PivotType.HIGH, 1473.4),
                hrPivot(2026, 5,  8, PivotType.LOW,  1417.5),
                hrPivot(2026, 5, 13, PivotType.LOW,  1352.4),
                hrPivot(2026, 5, 18, PivotType.LOW,  1318.7)
        ));
        SymbolContext ctx = SymbolContext.builder()
                .symbol("RELIANCE").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .pivotsByTf(byTf).build();

        List<Firing> emitted = RULE.evaluate(ctx, List.of(imp));
        assertEquals(1, emitted.size());
        Firing c = emitted.get(0);

        Map<String, Object> p = c.getPayload();
        // The load-bearing W2 retrace number — 84.3% within 0.5pp of blessed 85%.
        assertEquals(84.3, (double) p.get("w2_over_w1_pct"), 0.5,
                "W2 retrace must classify within 0.5pp of blessed 85%");
        assertEquals("atypical-W2", p.get("classification"));
        assertEquals(Boolean.TRUE, p.get("w2_in_progress"));
        assertEquals("OneHour", p.get("w2_source_tf"));
        assertEquals(1318.7, (double) p.get("w2_extreme_price"), 0.001);

        // Atypical-W2 → GRADUATED demote — drives MF2's prior below basePrior 0.25.
        assertEquals(PriorDelta.Kind.GRADUATED, c.getPriorDelta().kind());
        assertTrue(c.getPriorDelta().graduatedDelta() < 0.0,
                "atypical-W2 must demote MF2; got " + c.getPriorDelta().graduatedDelta());
    }

    @Test
    void eliminated_candidates_skipped() {
        Firing zz = zigzagCandidate("zz1", 1600.0, 1300.0, 1500.0);
        Firing elim = Firing.builder()
                .id("e1").ruleId(EwHardRuleValidatorRule.RULE_ID).family(Family.EW)
                .pass(Pass.P3_VALIDATION).firesOn(FiresOn.ELIMINATION)
                .refs(List.of("zz1"))
                .priorDelta(PriorDelta.eliminate("test", "3"))
                .build();

        List<Firing> emitted = RULE.evaluate(blankCtx(), List.of(zz, elim));
        assertTrue(emitted.isEmpty(), "eliminated candidate must be skipped");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static SymbolContext blankCtx() {
        return SymbolContext.builder()
                .symbol("TEST").tf("Week").asOf(LocalDate.of(2026, 5, 22)).build();
    }

    private static Firing zigzagCandidate(String id, double aStart, double aEnd, double bEnd) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        p.put("pivot_assignment", List.of(
                roleP("A_start", aStart, null),
                roleP("A_end", aEnd, null),
                roleP("B_end", bEnd, null)));
        return cand(id, p);
    }

    private static Firing impulseCandidateW0W1(String id, double w0, double w1End, String w1Date) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                roleP("W0", w0, null),
                roleP("W1_end", w1End, w1Date),
                roleP("W2_in_progress", null, null)));
        return cand(id, p);
    }

    private static Firing cand(String id, Map<String, Object> payload) {
        return Firing.builder()
                .id(id).ruleId(EwEnumerationRule.RULE_ID).family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.30).payload(payload).build();
    }

    private static Map<String, Object> roleP(String role, Double price, String date) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("price", price);
        m.put("date", date);
        return m;
    }

    private static MarketStructurePoint hrPivot(int y, int m, int d, PivotType k, double price) {
        return MarketStructurePoint.builder()
                .pivotType(k)
                .structureLabel(StructureLabel.FIRST)
                .timestamp(LocalDate.of(y, m, d).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant())
                .price(price)
                .atrAtPivot(10.0)
                .build();
    }
}
