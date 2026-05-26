package com.dtech.aitrader.v2.rules.ew;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.SpawnAnchorMode;
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
 * EwEnumerationRule — locks the two framings (MF1 zigzag + MF2 impulse) against the blessed
 * RELIANCE reference (`cde6bbc9`).
 *
 * <p>Acceptance: from anchor 1611.8 @ 2025-12-31 (corrective HIGH) the rule must emit BOTH:
 * <ul>
 *   <li>MF1 zigzag with A=1611.8→1290.0, B=1290→1473.4, C in progress, basePrior 0.45</li>
 *   <li>MF2 impulse with W0=1290, W1_end=1473.4, W2 in progress, basePrior 0.25</li>
 * </ul>
 */
class EwEnumerationRuleTest {

    private static final EwEnumerationRule RULE = new EwEnumerationRule();

    @Test
    void emits_MF1_zigzag_and_MF2_impulse_against_blessed_RELIANCE() {
        SymbolContext ctx = relianceWeeklyFixture();
        Firing anchorFact = blessedAnchorFact();

        List<Firing> emitted = RULE.evaluate(ctx, List.of(anchorFact));
        assertEquals(2, emitted.size(), "MF1 zigzag + MF2 impulse expected; got: " + emitted);

        for (Firing f : emitted) {
            assertEquals(EwEnumerationRule.RULE_ID, f.getRuleId());
            assertEquals(Family.EW, f.getFamily());
            assertEquals(Pass.P2_ENUMERATION, f.getPass());
            assertEquals(FiresOn.CANDIDATE, f.getFiresOn());
            assertEquals(SpawnAnchorMode.SAME_ANCHOR, f.getSpawnAnchorMode());
            assertEquals(List.of(anchorFact.getId()), f.getRefs());
        }

        // ── MF1: zigzag ────────────────────────────────────────────────────────
        Firing mf1 = emitted.stream()
                .filter(f -> "zigzag".equals(f.getPayload().get("form")))
                .findFirst().orElseThrow(() -> new AssertionError("no zigzag candidate"));
        assertEquals(0.45, mf1.getBasePrior(), 1e-9);

        List<Map<String, Object>> assignment = (List<Map<String, Object>>) mf1.getPayload().get("pivot_assignment");
        // A_start = anchor = 1611.8 @ 2025-12-31
        assertEquals(1611.8, (double) assignment.get(0).get("price"), 0.001);
        assertEquals("2025-12-31", assignment.get(0).get("date"));
        assertEquals("A_start", assignment.get(0).get("role"));
        // A_end = counter LL = 1290 @ 2026-04-01
        assertEquals(1290.0, (double) assignment.get(1).get("price"), 0.001);
        assertEquals("2026-04-01", assignment.get(1).get("date"));
        assertEquals("A_end", assignment.get(1).get("role"));
        // B_end = most recent HIGH after counter = 1473.4 @ 2026-04-29
        Map<String, Object> bEnd = assignment.stream()
                .filter(m -> "B_end".equals(m.get("role"))).findFirst().orElseThrow();
        assertEquals(1473.4, (double) bEnd.get("price"), 0.001);
        assertEquals("2026-04-29", bEnd.get("date"));

        // ── MF2: impulse ───────────────────────────────────────────────────────
        Firing mf2 = emitted.stream()
                .filter(f -> "impulse".equals(f.getPayload().get("form")))
                .findFirst().orElseThrow(() -> new AssertionError("no impulse candidate"));
        assertEquals(0.25, mf2.getBasePrior(), 1e-9);

        List<Map<String, Object>> imp = (List<Map<String, Object>>) mf2.getPayload().get("pivot_assignment");
        // W0 = counter LL = 1290
        assertEquals(1290.0, (double) imp.get(0).get("price"), 0.001);
        assertEquals("W0", imp.get(0).get("role"));
        // W1_end = 1473.4
        assertEquals(1473.4, (double) imp.get(1).get("price"), 0.001);
        assertEquals("W1_end", imp.get(1).get("role"));
    }

    @Test
    void no_emission_when_no_anchor_fact() {
        SymbolContext ctx = relianceWeeklyFixture();
        List<Firing> emitted = RULE.evaluate(ctx, List.of());
        assertTrue(emitted.isEmpty(),
                "no anchor → no candidates (Pass-2 strictly depends on Pass-1)");
    }

    @Test
    void no_emission_when_anchor_data_insufficient() {
        SymbolContext ctx = relianceWeeklyFixture();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("data_sufficient", Boolean.FALSE);
        Firing dataShort = Firing.builder()
                .id("anchor-short")
                .ruleId(EwMacroAnchorRule.RULE_ID)
                .pass(Pass.P1_STRUCTURAL)
                .firesOn(FiresOn.FACT)
                .family(Family.EW)
                .payload(p)
                .build();
        assertTrue(RULE.evaluate(ctx, List.of(dataShort)).isEmpty());
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private static Firing blessedAnchorFact() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("data_sufficient", Boolean.TRUE);
        payload.put("anchor_price", 1611.8);
        payload.put("anchor_date", "2025-12-31");
        payload.put("anchor_kind", "HIGH");
        payload.put("anchor_structure_label", "HH");
        payload.put("counter_extreme_price", 1290.0);
        payload.put("counter_extreme_date", "2026-04-01");
        payload.put("magnitude_pts", 321.8);
        payload.put("magnitude_pct", 19.97);
        payload.put("role_candidate", "corrective");
        payload.put("pivot_count", 57);
        return Firing.builder()
                .id("anchor-blessed")
                .ruleId(EwMacroAnchorRule.RULE_ID)
                .pass(Pass.P1_STRUCTURAL)
                .firesOn(FiresOn.FACT)
                .family(Family.EW)
                .payload(payload)
                .build();
    }

    /** RELIANCE Wk subset around the blessed corrective structure. */
    private static SymbolContext relianceWeeklyFixture() {
        // The pivots that matter for MF1/MF2 enumeration:
        // 2025-12-31 HH 1611.8     (anchor)
        // 2026-01-28 LH 1489.5
        // 2026-01-28 CHOCH_LOW 1335
        // 2026-04-01 LL 1290.0     (counter)
        // 2026-04-29 LH 1473.4     (B-end / W1-end)
        Map<String, List<MarketStructurePoint>> byTf = new LinkedHashMap<>();
        byTf.put("Week", List.of(
                pivot(2025, 12, 31, PivotType.HIGH, 1611.8, StructureLabel.HH),
                pivot(2026,  1, 28, PivotType.HIGH, 1489.5, StructureLabel.LH),
                pivot(2026,  1, 28, PivotType.LOW,  1335.0, StructureLabel.CHOCH_LOW),
                pivot(2026,  4,  1, PivotType.LOW,  1290.0, StructureLabel.LL),
                pivot(2026,  4, 29, PivotType.HIGH, 1473.4, StructureLabel.LH)
        ));
        return SymbolContext.builder()
                .symbol("RELIANCE").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .pivotsByTf(byTf)
                .build();
    }

    private static MarketStructurePoint pivot(int y, int m, int d, PivotType kind, double price,
                                                StructureLabel label) {
        return MarketStructurePoint.builder()
                .pivotType(kind)
                .structureLabel(label)
                .timestamp(LocalDate.of(y, m, d).atStartOfDay(ZoneId.of("Asia/Kolkata")).toInstant())
                .price(price)
                .atrAtPivot(50.0)
                .build();
    }
}
