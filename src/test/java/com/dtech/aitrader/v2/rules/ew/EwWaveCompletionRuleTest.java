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
 * EwWaveCompletionRule — spec-derived tests per SPEC-006 (1d3e3c25).
 *
 * <p>The discriminator is the PHASE-A bridge (sub-pivot-count + counter retest + max intra-leg
 * retrace) — labeled provisional in the rule output. PHASE-B target (pattern-shape primary)
 * not in scope yet. All values trace to blessed RELIANCE (cde6bbc9) and ICICIBANK (e409cb9e).
 */
class EwWaveCompletionRuleTest {

    private final EwWaveCompletionRule rule = new EwWaveCompletionRule();

    @Test
    void reliance_zigzag_B_with_1290_counter_retest_resolves_COMPLETE() {
        // Blessed RELIANCE Hr inside B-leg (counter 1290 @ 2026-04-01 → B_end 1473.4 @ 2026-04-29):
        // contains the 1290 LOW @ 2026-04-06 (100% counter retest), and 1359→1290 = 100% retrace
        // of the partial up-leg. Both criteria pass → state COMPLETE.
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, "2026-04-01", 1473.4, "2026-04-29");
        SymbolContext ctx = ctxWithHr(blessedRelianceBLegHrPivots());

        List<Firing> emitted = rule.evaluate(ctx, List.of(zz));
        assertEquals(1, emitted.size());
        Firing f = emitted.get(0);

        assertEquals(EwWaveCompletionRule.RULE_ID, f.getRuleId());
        assertEquals(Pass.P5_CONFIRMATION, f.getPass());
        assertEquals(FiresOn.CONFIRMATION, f.getFiresOn());
        assertEquals(Family.EW, f.getFamily());
        assertEquals(List.of("mf1"), f.getRefs());

        Map<String, Object> p = f.getPayload();
        assertEquals("B_end", p.get("role"));
        assertEquals("COMPLETE", p.get("new_state"),
                "blessed: 1290 retest + 100% retrace → B is COMPLETE");
        assertEquals("zigzag", p.get("candidate_form"));
        assertEquals(Boolean.TRUE, p.get("provisional"),
                "PHASE-A discriminator must self-declare provisional per SPEC-006 MOD-1");

        @SuppressWarnings("unchecked")
        Map<String, Object> sigs = (Map<String, Object>) p.get("discriminator_signals");
        assertEquals(Boolean.TRUE, sigs.get("counter_retest_pass"),
                "1290 Hr LOW within band of 1290 counter must register as retest");

        // COMPLETE state pays a small positive delta (sub-structure confirmed).
        assertEquals(PriorDelta.Kind.GRADUATED, f.getPriorDelta().kind());
        assertTrue(f.getPriorDelta().graduatedDelta() > 0.0,
                "COMPLETE confirmation must be a positive prior bump");
    }

    @Test
    void icicibank_zigzag_B_with_no_retest_resolves_IN_PROGRESS() {
        // Blessed ICICIBANK Hr inside B-leg (counter 1187.6 @ 2026-04-01 → B_end 1393.1 @ 2026-04-15):
        // lowest intermediate Hr LOW is 1275.9 → 7.4% above counter (no retest within 10% band),
        // deepest pullback 1333.5→1275.9 = 39.5% retrace (below 50% threshold). Both fail → state
        // IN_PROGRESS.
        Firing zz = zigzagCandidate("mf1", 1500.0, 1187.6, "2026-04-01", 1393.1, "2026-04-15");
        SymbolContext ctx = ctxWithHr(blessedIcicibankBLegHrPivots());

        List<Firing> emitted = rule.evaluate(ctx, List.of(zz));
        assertEquals(1, emitted.size());
        Firing f = emitted.get(0);

        Map<String, Object> p = f.getPayload();
        assertEquals("B_end", p.get("role"));
        assertEquals("IN_PROGRESS", p.get("new_state"),
                "blessed: no counter retest + 40% retrace → B is IN_PROGRESS (only B.A formed)");
        assertEquals(Boolean.TRUE, p.get("provisional"));

        @SuppressWarnings("unchecked")
        Map<String, Object> sigs = (Map<String, Object>) p.get("discriminator_signals");
        assertEquals(Boolean.FALSE, sigs.get("counter_retest_pass"),
                "lowest Hr LOW 1275.9 is 7.4% above counter 1187.6 — outside 10% band");
        double maxRetrace = ((Number) sigs.get("max_intra_leg_retrace_pct")).doubleValue();
        assertTrue(maxRetrace < 50.0,
                "max intra-leg retrace must be < 50% threshold; got " + maxRetrace);

        // IN_PROGRESS pays a negative delta (sub-structure denies completion).
        assertTrue(f.getPriorDelta().graduatedDelta() < 0.0,
                "IN_PROGRESS must demote the candidate's prior");
    }

    @Test
    void impulse_W1_inverted_discriminator() {
        // For an impulse candidate, W1 should look IMPULSIVE (no counter retest, shallow internal
        // retraces) to be COMPLETE. The same RELIANCE-style data with a 1290 counter retest +
        // 100% intra-leg retrace points to a CORRECTIVE shape, which means W1 is NOT a clean
        // 5-wave impulse → IN_PROGRESS (could be W1.iii or some other sub-degree, not the
        // complete W1).
        Firing imp = impulseCandidate("mf2", 1290.0, "2026-04-01", 1473.4, "2026-04-29");
        SymbolContext ctx = ctxWithHr(blessedRelianceBLegHrPivots());

        List<Firing> emitted = rule.evaluate(ctx, List.of(imp));
        assertEquals(1, emitted.size());
        Map<String, Object> p = emitted.get(0).getPayload();
        assertEquals("W1_end", p.get("role"));
        // Impulse W1 expects IMPULSIVE shape; the corrective-looking RELIANCE Hr makes it
        // IN_PROGRESS by the inverted discriminator.
        assertEquals("IN_PROGRESS", p.get("new_state"),
                "RELIANCE-style B (counter retest + 100% retrace) is corrective; for impulse W1 framing → IN_PROGRESS");
    }

    @Test
    void already_resolved_state_not_re_emitted() {
        // If the role's state is already COMPLETE (or IN_PROGRESS), skip — the rule only updates
        // CANDIDATE roles. This avoids double-firing across rounds.
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, "2026-04-01", 1473.4, "2026-04-29");
        // Override the B_end state to COMPLETE manually (as if a prior round already resolved).
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> assignment =
                (List<Map<String, Object>>) zz.getPayload().get("pivot_assignment");
        for (Map<String, Object> entry : assignment) {
            if ("B_end".equals(entry.get("role"))) {
                entry.put("state", "COMPLETE");
            }
        }
        SymbolContext ctx = ctxWithHr(blessedRelianceBLegHrPivots());
        assertTrue(rule.evaluate(ctx, List.of(zz)).isEmpty(),
                "rule must skip non-CANDIDATE states to keep firings idempotent across rounds");
    }

    @Test
    void eliminated_candidates_skipped() {
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, "2026-04-01", 1473.4, "2026-04-29");
        Firing elim = Firing.builder()
                .id("e1").ruleId(EwHardRuleValidatorRule.RULE_ID).family(Family.EW)
                .pass(Pass.P3_VALIDATION).firesOn(FiresOn.ELIMINATION)
                .refs(List.of("mf1"))
                .priorDelta(PriorDelta.eliminate("test", "3"))
                .build();
        SymbolContext ctx = ctxWithHr(blessedRelianceBLegHrPivots());
        assertTrue(rule.evaluate(ctx, List.of(zz, elim)).isEmpty(),
                "eliminated candidates must not be examined");
    }

    @Test
    void too_few_sub_pivots_no_firing() {
        // With < 2 sub-pivots in the leg span, the discriminator is indeterminate — rule skips.
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, "2026-04-01", 1473.4, "2026-04-29");
        SymbolContext ctx = ctxWithHr(List.of(
                hr("2026-04-15T03:45:00Z", 1320.0, PivotType.LOW, 30.0)));
        assertTrue(rule.evaluate(ctx, List.of(zz)).isEmpty(),
                "indeterminate sub-pivot count must result in no firing");
    }

    @Test
    void firing_carries_provisional_note_per_spec_006() {
        // SPEC-006 MOD-1 explicit: PHASE-A discriminator MUST self-declare provisional so future
        // readers (and PHASE-B implementers) know the retest proxy is the bridge, not the target.
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, "2026-04-01", 1473.4, "2026-04-29");
        SymbolContext ctx = ctxWithHr(blessedRelianceBLegHrPivots());
        Firing f = rule.evaluate(ctx, List.of(zz)).get(0);

        Map<String, Object> p = f.getPayload();
        assertEquals(Boolean.TRUE, p.get("provisional"));
        String note = (String) p.get("provisional_note");
        assertNotNull(note);
        assertTrue(note.contains("PHASE B") || note.contains("pattern-classifier"),
                "provisional note must reference the PHASE-B pattern-classifier seam; got: " + note);
    }

    @Test
    void discriminator_signals_payload_contains_required_fields() {
        // Acceptance (h): every cited level traces to real pivots; the firing's discriminator
        // signals must surface the values the auditor needs: sub_pivot_count, retest pass/fail,
        // retrace pct, threshold used. Locks the payload schema for downstream UI / impl-response.
        Firing zz = zigzagCandidate("mf1", 1611.8, 1290.0, "2026-04-01", 1473.4, "2026-04-29");
        SymbolContext ctx = ctxWithHr(blessedRelianceBLegHrPivots());
        Firing f = rule.evaluate(ctx, List.of(zz)).get(0);

        @SuppressWarnings("unchecked")
        Map<String, Object> sigs = (Map<String, Object>) f.getPayload().get("discriminator_signals");
        assertNotNull(sigs);
        assertTrue(sigs.containsKey("sub_tf"));
        assertTrue(sigs.containsKey("sub_pivot_count"));
        assertTrue(sigs.containsKey("counter_retest_pass"));
        assertTrue(sigs.containsKey("counter_retest_band_pct_used"));
        assertTrue(sigs.containsKey("max_intra_leg_retrace_pct"));
        assertTrue(sigs.containsKey("retrace_threshold_pct_used"));
        assertTrue(sigs.containsKey("pattern_shape"),
                "pattern_shape key must exist (null in PHASE A; populated in PHASE B)");
        assertNull(sigs.get("pattern_shape"),
                "pattern_shape must be null in PHASE A; PHASE B populates it");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static SymbolContext ctxWithHr(List<MarketStructurePoint> hr) {
        Map<String, List<MarketStructurePoint>> byTf = new LinkedHashMap<>();
        byTf.put("OneHour", hr);
        return SymbolContext.builder()
                .symbol("TEST").tf("Week").asOf(LocalDate.of(2026, 5, 22))
                .pivotsByTf(byTf).build();
    }

    /** RELIANCE Hr pivots within (2026-04-01, 2026-04-29) — verbatim from dffe1f75. */
    private static List<MarketStructurePoint> blessedRelianceBLegHrPivots() {
        return List.of(
                hr("2026-04-02T03:45:00Z", 1328.0, PivotType.LOW, 32.9),
                hr("2026-04-06T03:45:00Z", 1359.0, PivotType.HIGH, 33.4),
                hr("2026-04-06T06:45:00Z", 1290.0, PivotType.LOW, 30.5),   // counter retest!
                hr("2026-04-08T08:45:00Z", 1350.6, PivotType.HIGH, 60.5),
                hr("2026-04-09T08:45:00Z", 1326.3, PivotType.LOW, 48.2),
                hr("2026-04-10T08:45:00Z", 1352.3, PivotType.HIGH, 60.4),
                hr("2026-04-13T06:45:00Z", 1310.0, PivotType.LOW, 34.6),
                hr("2026-04-16T03:45:00Z", 1353.8, PivotType.HIGH, 49.9),
                hr("2026-04-16T06:45:00Z", 1330.0, PivotType.LOW, 48.3),
                hr("2026-04-20T05:45:00Z", 1373.0, PivotType.HIGH, 69.0),
                hr("2026-04-22T03:45:00Z", 1349.1, PivotType.LOW, 50.7),
                hr("2026-04-22T09:45:00Z", 1366.0, PivotType.HIGH, 55.4),
                hr("2026-04-27T03:45:00Z", 1311.0, PivotType.LOW, 44.8),
                hr("2026-04-28T04:45:00Z", 1387.8, PivotType.HIGH, 71.8));
    }

    /** ICICIBANK Hr pivots within (2026-04-01, 2026-04-15) — verbatim from 85362d84 per owner. */
    private static List<MarketStructurePoint> blessedIcicibankBLegHrPivots() {
        return List.of(
                hr("2026-04-02T03:45:00Z", 1333.5, PivotType.HIGH, 60.0),
                hr("2026-04-03T03:45:00Z", 1275.9, PivotType.LOW, 30.0),   // 7.4% above counter — no retest
                hr("2026-04-06T03:45:00Z", 1324.0, PivotType.HIGH, 55.0),
                hr("2026-04-07T03:45:00Z", 1296.7, PivotType.LOW, 40.0),
                hr("2026-04-09T03:45:00Z", 1366.9, PivotType.HIGH, 70.0),
                hr("2026-04-10T03:45:00Z", 1334.0, PivotType.LOW, 45.0),
                hr("2026-04-13T03:45:00Z", 1376.4, PivotType.HIGH, 72.0),
                hr("2026-04-14T03:45:00Z", 1353.0, PivotType.LOW, 50.0));
    }

    private static MarketStructurePoint hr(String iso, double price, PivotType k, double rsi) {
        return MarketStructurePoint.builder()
                .pivotType(k).structureLabel(StructureLabel.FIRST)
                .timestamp(Instant.parse(iso)).price(price)
                .atrAtPivot(10.0).rsiAtPivot(rsi).build();
    }

    private static Firing zigzagCandidate(String id, double aStart, double aEnd, String aEndDate,
                                            double bEnd, String bEndDate) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        p.put("pivot_assignment", List.of(
                role("A_start", aStart, null, "COMPLETE"),
                role("A_end", aEnd, aEndDate, "COMPLETE"),
                role("B_start", aEnd, aEndDate, "COMPLETE"),
                role("B_end", bEnd, bEndDate, "CANDIDATE"),
                role("C", null, null, "NOT_STARTED")));
        return Firing.builder()
                .id(id).ruleId(EwEnumerationRule.RULE_ID).family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.45).payload(p).build();
    }

    private static Firing impulseCandidate(String id, double w0, String w0Date,
                                             double w1End, String w1EndDate) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        p.put("pivot_assignment", List.of(
                role("W0", w0, w0Date, "COMPLETE"),
                role("W1_end", w1End, w1EndDate, "CANDIDATE"),
                role("W2", null, null, "NOT_STARTED")));
        return Firing.builder()
                .id(id).ruleId(EwEnumerationRule.RULE_ID).family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.25).payload(p).build();
    }

    private static Map<String, Object> role(String r, Double price, String date, String state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", r);
        m.put("price", price);
        m.put("date", date);
        m.put("state", state);
        return m;
    }
}
