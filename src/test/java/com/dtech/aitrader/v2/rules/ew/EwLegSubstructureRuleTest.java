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
 * EwLegSubstructureRule — locks the Rule-0.95 leg substructure classification against blessed
 * RELIANCE reference {@code cde6bbc9}:
 *
 * <ul>
 *   <li>MF1 zigzag C-in-progress: Hr sub-pivots after 2026-04-29 B_end = 7 pivots →
 *       5-wave-impulsive → CONFIRM (C should be impulsive)</li>
 *   <li>MF2 impulse W2-in-progress on the same Hr structure → CONTRADICT (W2 should be corrective,
 *       not impulsive)</li>
 * </ul>
 */
class EwLegSubstructureRuleTest {

    private static final EwLegSubstructureRule RULE = new EwLegSubstructureRule();

    @Test
    void zigzag_C_with_5wave_impulsive_hr_confirms() {
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, 1473.4, "2026-04-29");
        SymbolContext ctx = ctxWithHrPivots(blessedHrSubPivots());

        List<Firing> emitted = RULE.evaluate(ctx, List.of(zz));
        assertEquals(1, emitted.size());
        Firing c = emitted.get(0);

        assertEquals(EwLegSubstructureRule.RULE_ID, c.getRuleId());
        assertEquals(Pass.P5_CONFIRMATION, c.getPass());
        assertEquals(FiresOn.CONFIRMATION, c.getFiresOn());
        assertEquals(List.of("mf1"), c.getRefs());

        Map<String, Object> p = c.getPayload();
        assertEquals("C", p.get("leg_kind"));
        assertEquals("OneHour", p.get("sub_tf"));
        assertEquals(7, ((Number) p.get("sub_pivot_count")).intValue(),
                "blessed RELIANCE has 7 Hr pivots after 2026-04-29");
        assertEquals("5-wave-impulsive", p.get("sub_structure"));
        assertEquals(Boolean.TRUE, p.get("confirms_framing"));

        assertEquals(PriorDelta.Kind.GRADUATED, c.getPriorDelta().kind());
        assertTrue(c.getPriorDelta().graduatedDelta() > 0.0,
                "5-wave impulsive C-leg must CONFIRM (positive delta); got "
                        + c.getPriorDelta().graduatedDelta());
    }

    @Test
    void impulse_W2_with_5wave_impulsive_hr_contradicts() {
        Firing imp = impulseCandidateW0W1("mf2", 1290.0, 1473.4, "2026-04-29");
        SymbolContext ctx = ctxWithHrPivots(blessedHrSubPivots());

        List<Firing> emitted = RULE.evaluate(ctx, List.of(imp));
        assertEquals(1, emitted.size());
        Firing c = emitted.get(0);

        Map<String, Object> p = c.getPayload();
        assertEquals("W2", p.get("leg_kind"));
        assertEquals("5-wave-impulsive", p.get("sub_structure"));
        assertEquals(Boolean.FALSE, p.get("confirms_framing"));

        assertTrue(c.getPriorDelta().graduatedDelta() < 0.0,
                "5-wave impulsive structure inside W2 must CONTRADICT MF2; got "
                        + c.getPriorDelta().graduatedDelta());
    }

    @Test
    void zigzag_C_with_3wave_corrective_hr_contradicts() {
        Firing zz = zigzagCandidate("zz", 1600.0, 1300.0, 1500.0, "2026-04-29");
        List<MarketStructurePoint> tiny = List.of(
                hrPivot(2026, 5,  5, PivotType.LOW,  1450.0),
                hrPivot(2026, 5, 10, PivotType.HIGH, 1480.0),
                hrPivot(2026, 5, 15, PivotType.LOW,  1440.0));  // 3 pivots = corrective
        SymbolContext ctx = ctxWithHrPivots(tiny);

        List<Firing> emitted = RULE.evaluate(ctx, List.of(zz));
        assertEquals(1, emitted.size());
        Map<String, Object> p = emitted.get(0).getPayload();
        assertEquals("3-wave-corrective", p.get("sub_structure"));
        assertEquals(Boolean.FALSE, p.get("confirms_framing"));
        assertTrue(emitted.get(0).getPriorDelta().graduatedDelta() < 0.0);
    }

    @Test
    void too_few_pivots_indeterminate_no_firing() {
        Firing zz = zigzagCandidate("zz", 1600.0, 1300.0, 1500.0, "2026-04-29");
        List<MarketStructurePoint> one = List.of(
                hrPivot(2026, 5,  5, PivotType.LOW, 1450.0));
        SymbolContext ctx = ctxWithHrPivots(one);
        assertTrue(RULE.evaluate(ctx, List.of(zz)).isEmpty(),
                "with <2 sub-pivots the rule must not fire");
    }

    @Test
    void four_pivots_indeterminate_no_firing() {
        Firing zz = zigzagCandidate("zz", 1600.0, 1300.0, 1500.0, "2026-04-29");
        List<MarketStructurePoint> four = List.of(
                hrPivot(2026, 5,  5, PivotType.LOW,  1450.0),
                hrPivot(2026, 5,  8, PivotType.HIGH, 1480.0),
                hrPivot(2026, 5, 11, PivotType.LOW,  1440.0),
                hrPivot(2026, 5, 14, PivotType.HIGH, 1470.0));
        SymbolContext ctx = ctxWithHrPivots(four);
        assertTrue(RULE.evaluate(ctx, List.of(zz)).isEmpty(),
                "4 pivots = indeterminate, no firing");
    }

    @Test
    void eliminated_candidates_skipped() {
        Firing zz = zigzagCandidate("zz1", 1600.0, 1300.0, 1500.0, "2026-04-29");
        Firing elim = Firing.builder()
                .id("e1").ruleId(EwHardRuleValidatorRule.RULE_ID).family(Family.EW)
                .pass(Pass.P3_VALIDATION).firesOn(FiresOn.ELIMINATION)
                .refs(List.of("zz1"))
                .priorDelta(PriorDelta.eliminate("test", "3"))
                .build();
        SymbolContext ctx = ctxWithHrPivots(blessedHrSubPivots());
        assertTrue(RULE.evaluate(ctx, List.of(zz, elim)).isEmpty(),
                "eliminated candidates must be skipped");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Blessed RELIANCE: Hr pivots between 2026-04-29 (W1_end / B_end) and asOf — 7 alternating swings. */
    private static List<MarketStructurePoint> blessedHrSubPivots() {
        return List.of(
                hrPivot(2026, 4, 30, PivotType.LOW,  1393.1),
                hrPivot(2026, 5,  5, PivotType.HIGH, 1473.4),
                hrPivot(2026, 5,  8, PivotType.LOW,  1417.5),
                hrPivot(2026, 5,  8, PivotType.HIGH, 1442.8),
                hrPivot(2026, 5, 13, PivotType.LOW,  1352.4),
                hrPivot(2026, 5, 14, PivotType.HIGH, 1378.0),
                hrPivot(2026, 5, 18, PivotType.LOW,  1318.7));
    }

    private static SymbolContext ctxWithHrPivots(List<MarketStructurePoint> hr) {
        Map<String, List<MarketStructurePoint>> byTf = new LinkedHashMap<>();
        byTf.put("OneHour", hr);
        return SymbolContext.builder()
                .symbol("RELIANCE").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .pivotsByTf(byTf).build();
    }

    private static Firing zigzagCandidate(String id, double aStart, double aEnd, double bEnd, String bEndDate) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        p.put("pivot_assignment", List.of(
                roleP("A_start", aStart, null),
                roleP("A_end", aEnd, null),
                roleP("B_end", bEnd, bEndDate)));
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
