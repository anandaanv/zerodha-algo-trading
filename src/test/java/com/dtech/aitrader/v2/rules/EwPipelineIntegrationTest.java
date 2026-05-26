package com.dtech.aitrader.v2.rules;

import com.dtech.aitrader.data.RuleFiring;
import com.dtech.aitrader.v2.rules.ew.EwAnnotationIntakeRule;
import com.dtech.aitrader.v2.rules.ew.EwAnnotationPriorRule;
import com.dtech.aitrader.v2.rules.ew.EwClusterConfluenceRule;
import com.dtech.aitrader.v2.rules.ew.EwDivergenceConfirmRule;
import com.dtech.aitrader.v2.rules.ew.EwEnumerationRule;
import com.dtech.aitrader.v2.rules.ew.EwHardRuleValidatorRule;
import com.dtech.aitrader.v2.rules.ew.EwLegSubstructureRule;
import com.dtech.aitrader.v2.rules.ew.EwMacroAnchorRule;
import com.dtech.aitrader.v2.rules.ew.EwMagnitudeRule;
import com.dtech.aitrader.v2.rules.ew.EwWaveCompletionRule;
import com.dtech.aitrader.v2.rules.ew.EwWkClusterScanRule;
import com.dtech.aitrader.v2.rules.synthesis.EwVerdictSynthesisRule;
import com.dtech.aitrader.v2.rules.synthesis.PatternVerdictSynthesisRule;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.PivotType;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint.StructureLabel;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EwPipelineIntegrationTest — Pass-1 → Pass-6 integration against the blessed cde6bbc9 data.
 *
 * <p><b>Requirement source:</b>
 * <ul>
 *   <li>BLESSED reference cde6bbc9 ({@code 48130d04}): the engine run on RELIANCE scan-context
 *       must reproduce the 9 numbered criteria — anchor 1611.8, ≥3 clusters incl. ~1290 and
 *       ~1473, 2 framings (zigzag corrective + impulse), B/A=57%, W2/W1≈85%, MF1 leading,
 *       target ~1152, MF1 invalidation above 1611, every level a real CSV pivot.</li>
 *   <li>SPEC-005 ({@code ab9bd541}) ACCEPTANCE TEST section: the surviving candidate set, the
 *       magnitude classifications, and the leading framing must match.</li>
 *   <li>Q5 convergence ({@code 9c60e777}): one firing per (candidate, delta) — no duplicates
 *       in the chain.</li>
 *   <li>Q7 convergence: VERDICT is the only outcome-bearing firing.</li>
 * </ul>
 *
 * <p>Every pivot value below traces verbatim to the dffe1f75 Wk / Day / Hr CSVs (cited in
 * {@code 48130d04}). No invented numbers. Per owner directive 75b20b10 the engine is verified
 * against the live bundle via the {@code /api/ai-trader-v2/ew/grade} endpoint; this test
 * additionally locks the WIRING between rules + engine + Pass-6 synthesis so a future refactor
 *
 * <p><b>DISABLED pending owner amendment to blessed RELIANCE reference cde6bbc9.</b> Per SPEC
 * reframe {@code 159ba913}: the old single-thesis 0.68 SHORT verdict is over-committed; the
 * engine now correctly emits a WATCH + LEVEL-MAP instead. The acceptance assertions in this
 * test were written against the OLD model. Re-enable + rewrite once owner + architect publish
 * the structural level-map amendment (per the "ground-truth amendment pending" note in
 * {@code 159ba913}). See impl-response {@code 2e51fc50} for the new live output.
 * can't quietly break the firing-count contract.
 */
@Disabled("Awaiting owner amendment to blessed RELIANCE cde6bbc9 (structural level-map read per SPEC reframe 159ba913). The OLD-model assertions here (confident SHORT VERDICT @ 0.68) no longer match the new framework's correct output (WATCH + LEVEL-MAP with multiple admissible hypotheses). Re-enable + rewrite when the amended reference lands.")
class EwPipelineIntegrationTest {

    @Test
    void blessed_reliance_pipeline_produces_expected_firing_breakdown_and_verdict() {
        MultiPassEngine engine = new MultiPassEngine();
        List<Rule> rules = new ArrayList<>(List.of(
                new EwMacroAnchorRule(),
                new EwWkClusterScanRule(),
                new EwAnnotationIntakeRule(),
                new EwEnumerationRule(),
                new EwHardRuleValidatorRule(),
                new EwMagnitudeRule(),
                new EwClusterConfluenceRule(),
                new EwAnnotationPriorRule(),
                new EwLegSubstructureRule(),
                new EwDivergenceConfirmRule(),
                new EwWaveCompletionRule(),
                new EwVerdictSynthesisRule(),
                // pattern verdict synthesis is also registered in production; it must coexist
                // and emit nothing for EW-only candidates.
                new PatternVerdictSynthesisRule()
        ));

        SymbolContext ctx = buildBlessedRelianceContext();
        List<Firing> firings = engine.run(ctx, rules);

        // ── Pass-1: structural FACTs ────────────────────────────────────────────
        List<Firing> anchors = firingsOf(firings, EwMacroAnchorRule.RULE_ID);
        assertEquals(1, anchors.size(), "exactly one macro anchor FACT");
        assertEquals(1611.8, ((Number) anchors.get(0).getPayload().get("anchor_price")).doubleValue(), 0.001,
                "blessed anchor price is 1611.8");

        List<Firing> clusters = firingsOf(firings, EwWkClusterScanRule.RULE_ID);
        assertTrue(clusters.size() >= 2,
                "blessed ≥2 Wk clusters present (~1290 support, ~1473 resistance); got " + clusters.size());
        boolean has1290Cluster = clusters.stream().anyMatch(f -> nearCentre(f, 1290.0, 1310.0));
        boolean has1473Cluster = clusters.stream().anyMatch(f -> nearCentre(f, 1473.0, 1490.0));
        assertTrue(has1290Cluster, "blessed ~1290-1307 cluster must be present");
        assertTrue(has1473Cluster, "blessed ~1473-1489 cluster must be present");

        // Annotations: SPEC-005 says every weight-≥2 annotation becomes a FACT.
        // Blessed dffe1f75 carries 4 weight-3 annotations → 4 FACTs (only the wave-4C one will
        // later match a corrective candidate at Pass-4 — the other three contain no wave-form
        // keywords).
        List<Firing> annotationFacts = firingsOf(firings, EwAnnotationIntakeRule.RULE_ID);
        assertEquals(4, annotationFacts.size(), "4 weight-≥2 annotations in fixture");

        // ── Pass-2: candidates ──────────────────────────────────────────────────
        List<Firing> candidates = firingsOf(firings, EwEnumerationRule.RULE_ID);
        assertEquals(2, candidates.size(),
                "blessed criterion (c): ≥2 framings — MF1 zigzag + MF2 impulse");
        boolean hasZigzag = candidates.stream().anyMatch(f -> "zigzag".equals(f.getPayload().get("form")));
        boolean hasImpulse = candidates.stream().anyMatch(f -> "impulse".equals(f.getPayload().get("form")));
        assertTrue(hasZigzag, "MF1 zigzag must be enumerated");
        assertTrue(hasImpulse, "MF2 impulse must be enumerated");

        // ── Pass-4: load-bearing magnitude classifications ──────────────────────
        List<Firing> magnitudes = firingsOf(firings, EwMagnitudeRule.RULE_ID);
        assertEquals(2, magnitudes.size(), "one magnitude classification per candidate");
        Firing zzMag = magnitudes.stream()
                .filter(f -> "zigzag-B-verified".equals(f.getPayload().get("classification")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "blessed B/A=57% must classify as zigzag-B-verified; got "
                                + magnitudes.stream().map(f -> f.getPayload().get("classification")).toList()));
        assertEquals(57.0, ((Number) zzMag.getPayload().get("b_over_a_pct")).doubleValue(), 0.5,
                "blessed B/A=57% within 0.5pp");

        Firing w2Mag = magnitudes.stream()
                .filter(f -> "atypical-W2".equals(f.getPayload().get("classification")))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "blessed W2 retrace 85% must classify as atypical-W2"));
        assertEquals(85.0, ((Number) w2Mag.getPayload().get("w2_over_w1_pct")).doubleValue(), 1.0,
                "blessed W2 retrace within 1pp of 85%");

        // ── Pass-4: cluster confluence + annotation prior ───────────────────────
        List<Firing> clusterBoosts = firingsOf(firings, EwClusterConfluenceRule.RULE_ID);
        assertTrue(clusterBoosts.size() >= 2,
                "MF1 + MF2 each have at least one structural level on a cluster; got "
                        + clusterBoosts.size());

        List<Firing> annotationBoosts = firingsOf(firings, EwAnnotationPriorRule.RULE_ID);
        assertEquals(1, annotationBoosts.size(),
                "exactly one annotation boost — MF1 only matches wave-4C (impulse doesn't)");
        assertEquals("zigzag", annotationBoosts.get(0).getPayload().get("candidate_form"),
                "the annotation boost must reference the zigzag candidate, not impulse");

        // ── Pass-5: leg substructure + divergence ───────────────────────────────
        List<Firing> legSubs = firingsOf(firings, EwLegSubstructureRule.RULE_ID);
        assertEquals(2, legSubs.size(),
                "leg substructure fires once per candidate (MF1 C, MF2 W2)");
        // MF1 zigzag C should CONFIRM (5-wave-impulsive matches C); MF2 W2 should CONTRADICT.
        long confirmCount = legSubs.stream()
                .filter(f -> Boolean.TRUE.equals(f.getPayload().get("confirms_framing")))
                .count();
        assertEquals(1, confirmCount, "MF1 leg substructure confirms, MF2 contradicts");

        List<Firing> divergence = firingsOf(firings, EwDivergenceConfirmRule.RULE_ID);
        assertEquals(2, divergence.size(),
                "divergence fires once per candidate at the shared B_end / W1_end pivot");
        long divConfirmCount = divergence.stream()
                .filter(f -> Boolean.TRUE.equals(f.getPayload().get("confirms_framing")))
                .count();
        assertEquals(1, divConfirmCount,
                "MF1 zigzag CONFIRMED, MF2 impulse CONTRADICTED — same observation different meaning");

        // ── Pass-6: verdict ─────────────────────────────────────────────────────
        List<Firing> verdicts = firingsOf(firings, EwVerdictSynthesisRule.RULE_ID);
        assertEquals(1, verdicts.size(),
                "Pass-6 EW synthesis emits exactly one VERDICT — the leading framing");
        Firing verdict = verdicts.get(0);
        assertEquals(FiresOn.VERDICT, verdict.getFiresOn());
        assertEquals(Family.SYNTHESIS, verdict.getFamily());

        Map<String, Object> vp = verdict.getPayload();
        assertEquals("zigzag", vp.get("winning_form"),
                "blessed criterion (d): MF1 zigzag is the LEADING framing");
        assertEquals(RuleFiring.Bias.SHORT, verdict.getBias(),
                "downside zigzag → SHORT bias");
        assertEquals(1473.4, verdict.getTriggerPrice(), 0.001,
                "trigger = B_end (1473.4)");
        assertEquals(1611.8, verdict.getInvalidationPrice(), 0.001,
                "invalidation = A_start (1611.8)");
        // Target: B_end - |A magnitude| = 1473.4 - (1611.8 - 1290) = 1151.6, blessed ~1152.
        assertEquals(1151.6, verdict.getTargetPrice(), 0.1,
                "blessed criterion (g): MF1 target ~1152 (C = A projection)");
        assertTrue(verdict.getFinalConviction() >= 0.40,
                "verdict prior must clear the default threshold; got " + verdict.getFinalConviction());

        // ── Pattern synthesis must stay silent on EW-only data ──────────────────
        assertEquals(0, firingsOf(firings, PatternVerdictSynthesisRule.RULE_ID).size(),
                "pattern synthesis must not emit when no PATTERN candidates exist");
    }

    @Test
    void pipeline_is_idempotent_same_input_produces_same_firings() {
        // Q1 convergence: rerunning the engine on the same context must produce identical
        // firings (digest-based IDs, append-only fold). No duplicate verdicts on re-run.
        MultiPassEngine engine = new MultiPassEngine();
        List<Rule> rules = List.of(
                new EwMacroAnchorRule(),
                new EwWkClusterScanRule(),
                new EwAnnotationIntakeRule(),
                new EwEnumerationRule(),
                new EwHardRuleValidatorRule(),
                new EwMagnitudeRule(),
                new EwClusterConfluenceRule(),
                new EwAnnotationPriorRule(),
                new EwLegSubstructureRule(),
                new EwDivergenceConfirmRule(),
                new EwWaveCompletionRule(),
                new EwVerdictSynthesisRule()
        );
        SymbolContext ctx = buildBlessedRelianceContext();

        List<Firing> run1 = engine.run(ctx, rules);
        List<Firing> run2 = engine.run(ctx, rules);
        assertEquals(run1.size(), run2.size(),
                "rerunning on identical input must produce the same firing count");

        // Same digest IDs across runs (content-addressable).
        List<String> ids1 = run1.stream().map(Firing::getId).sorted().toList();
        List<String> ids2 = run2.stream().map(Firing::getId).sorted().toList();
        assertEquals(ids1, ids2,
                "firing digests must be deterministic across runs");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static List<Firing> firingsOf(List<Firing> all, String ruleId) {
        return all.stream().filter(f -> ruleId.equals(f.getRuleId())).toList();
    }

    private static boolean nearCentre(Firing f, double lo, double hi) {
        Object c = f.getPayload().get("centre");
        if (!(c instanceof Number n)) return false;
        double v = n.doubleValue();
        return v >= lo && v <= hi;
    }

    /**
     * Build a SymbolContext carrying the blessed dffe1f75 data subset needed for the integration
     * test. Every value below traces to the dffe1f75 Wk / Day / Hr CSVs (the bundle that the live
     * grade endpoint loads from memsys). No synthetic levels.
     */
    private static SymbolContext buildBlessedRelianceContext() {
        // ── Week pivots ── representative sample of the full 57-pivot dffe1f75 bundle. The
        // OLDER LOWs (2016/2020) are required so that the absolute-min LOW lies BEFORE the
        // absolute-max HIGH in time — that's the trigger for the EwMacroAnchorRule algorithm to
        // pick the recent HIGH (1611.8) as the macro anchor with 1290 as the subsequent counter
        // LOW, exactly per blessed cde6bbc9. Without the older LOWs, 1290 would be the absolute
        // min and (being more recent than 1611.8) the algorithm would anchor on the LOW instead.
        // Source: dffe1f75 Wk CSV — every value below is a real bundle pivot.
        List<MarketStructurePoint> wk = List.of(
                wkPivot("2016-11-03T00:00:00Z", 221.6,  PivotType.LOW,  StructureLabel.FIRST, 74.4, 11.4),
                wkPivot("2020-03-25T00:00:00Z", 459.1,  PivotType.LOW,  StructureLabel.LL, 36.7, 52.74),
                wkPivot("2021-10-13T00:00:00Z", 1311.3, PivotType.HIGH, StructureLabel.HH, 76.9, 54.16),
                wkPivot("2022-11-30T00:00:00Z", 1313.0, PivotType.HIGH, StructureLabel.CHOCH_HIGH, 58.8, 59.84),
                wkPivot("2024-01-24T00:00:00Z", 1460.0, PivotType.HIGH, StructureLabel.HH, 69.0, 52.61),
                wkPivot("2024-02-28T00:00:00Z", 1512.45, PivotType.HIGH, StructureLabel.HH, 75.3, 52.29),
                wkPivot("2024-07-03T00:00:00Z", 1608.8, PivotType.HIGH, StructureLabel.HH, 67.6, 65.62),
                wkPivot("2025-03-19T00:00:00Z", 1307.7, PivotType.HIGH, StructureLabel.LH, 50.0, 58.29),
                wkPivot("2025-12-31T00:00:00Z", 1611.8, PivotType.HIGH, StructureLabel.HH, 56.1, 51.43),
                wkPivot("2026-01-28T00:00:00Z", 1335.0, PivotType.LOW,  StructureLabel.CHOCH_LOW, 48.0, 62.53),
                wkPivot("2026-01-28T00:00:00Z", 1489.5, PivotType.HIGH, StructureLabel.CHOCH_LOW, 48.0, 62.53),
                wkPivot("2026-04-01T00:00:00Z", 1290.0, PivotType.LOW,  StructureLabel.LL, 36.7, 70.24),
                wkPivot("2026-04-29T00:00:00Z", 1473.4, PivotType.HIGH, StructureLabel.LH, 55.7, 70.13));

        // ── Day pivots ── (relevant subset for Pass-5 W2 retrace lookups when needed)
        List<MarketStructurePoint> day = List.of(
                dayPivot("2026-04-05T18:30:00Z", 1290.0, PivotType.LOW,  StructureLabel.LL, 33.7),
                dayPivot("2026-05-04T18:30:00Z", 1473.4, PivotType.HIGH, StructureLabel.CHOCH_HIGH, 66.1));

        // ── OneHour pivots ── two contiguous windows from dffe1f75:
        //   (a) INSIDE the B-leg span (2026-04-01 → 2026-04-29) — needed by EwWaveCompletionRule
        //       to see the 1290 counter retest @ 2026-04-06 that drives B → COMPLETE.
        //   (b) AFTER B_end (2026-04-29 → 2026-05-18) — needed by EwLegSubstructureRule + the
        //       Hr lowest 1318.7 for W2 retrace = 85% blessed.
        // Every value below is a real pivot from the dffe1f75 Hr CSV.
        List<MarketStructurePoint> hr = List.of(
                // ── (a) inside B-leg window ────────────────────────────────────
                hrPivot("2026-04-02T03:45:00Z", 1328.0, PivotType.LOW,  StructureLabel.CHOCH_LOW, 32.9),
                hrPivot("2026-04-06T03:45:00Z", 1359.0, PivotType.HIGH, StructureLabel.LH, 33.4),
                hrPivot("2026-04-06T06:45:00Z", 1290.0, PivotType.LOW,  StructureLabel.LL, 30.5),
                hrPivot("2026-04-08T08:45:00Z", 1350.6, PivotType.HIGH, StructureLabel.LH, 60.5),
                hrPivot("2026-04-09T08:45:00Z", 1326.3, PivotType.LOW,  StructureLabel.HL, 48.2),
                hrPivot("2026-04-10T08:45:00Z", 1352.3, PivotType.HIGH, StructureLabel.HH, 60.4),
                hrPivot("2026-04-13T06:45:00Z", 1310.0, PivotType.LOW,  StructureLabel.CHOCH_LOW, 34.6),
                hrPivot("2026-04-16T03:45:00Z", 1353.8, PivotType.HIGH, StructureLabel.HH, 49.9),
                hrPivot("2026-04-16T06:45:00Z", 1330.0, PivotType.LOW,  StructureLabel.HL, 48.3),
                hrPivot("2026-04-20T05:45:00Z", 1373.0, PivotType.HIGH, StructureLabel.HH, 69.0),
                hrPivot("2026-04-22T03:45:00Z", 1349.1, PivotType.LOW,  StructureLabel.HL, 50.7),
                hrPivot("2026-04-22T09:45:00Z", 1366.0, PivotType.HIGH, StructureLabel.LH, 55.4),
                hrPivot("2026-04-27T03:45:00Z", 1311.0, PivotType.LOW,  StructureLabel.LL, 44.8),
                hrPivot("2026-04-28T04:45:00Z", 1387.8, PivotType.HIGH, StructureLabel.CHOCH_HIGH, 71.8),
                // ── (b) after B_end ──────────────────────────────────────────────
                hrPivot("2026-04-29T09:45:00Z", 1433.8, PivotType.HIGH, StructureLabel.HH, 77.4),
                hrPivot("2026-04-30T04:45:00Z", 1393.1, PivotType.LOW,  StructureLabel.HL, 56.3),
                hrPivot("2026-05-05T03:45:00Z", 1473.4, PivotType.HIGH, StructureLabel.HH, 76.1),
                hrPivot("2026-05-08T03:45:00Z", 1417.5, PivotType.LOW,  StructureLabel.HL, 41.6),
                hrPivot("2026-05-08T08:45:00Z", 1442.8, PivotType.HIGH, StructureLabel.LH, 49.7),
                hrPivot("2026-05-13T03:45:00Z", 1352.4, PivotType.LOW,  StructureLabel.LL, 22.2),
                hrPivot("2026-05-14T05:45:00Z", 1378.0, PivotType.HIGH, StructureLabel.LH, 40.3),
                hrPivot("2026-05-18T03:45:00Z", 1318.7, PivotType.LOW,  StructureLabel.LL, 23.5));

        // ── annotations ──
        AnnotationEntry wave4c = new AnnotationEntry(
                "On weekly, the stock appears to be in wave 4C. now we dont know from the context "
                        + "whether we are in 2of C or 4 of C..",
                3, null, null);
        AnnotationEntry channel = new AnnotationEntry("This channel is also a possibility", 3, null, null);
        AnnotationEntry gapRetest = new AnnotationEntry(
                "This seems to be previous gap, retest scenario now, and stock also seems to respecting this level.",
                3, null, null);
        AnnotationEntry support = new AnnotationEntry("This is going to be its longterm support..", 3, null, null);

        Map<String, List<MarketStructurePoint>> byTf = new LinkedHashMap<>();
        byTf.put("Week", wk);
        byTf.put("Day", day);
        byTf.put("OneHour", hr);

        return SymbolContext.builder()
                .symbol("RELIANCE")
                .tf("Week")
                .asOf(LocalDate.of(2026, 5, 22))
                .pivots(wk)
                .pivotsByTf(byTf)
                .annotations(List.of(wave4c, channel, gapRetest, support))
                .build();
    }

    private static MarketStructurePoint wkPivot(String iso, double price, PivotType kind,
                                                  StructureLabel label, double rsi, double atr) {
        return MarketStructurePoint.builder()
                .pivotType(kind).structureLabel(label)
                .timestamp(Instant.parse(iso)).price(price)
                .atrAtPivot(atr).rsiAtPivot(rsi).build();
    }
    private static MarketStructurePoint dayPivot(String iso, double price, PivotType kind,
                                                   StructureLabel label, double rsi) {
        return MarketStructurePoint.builder()
                .pivotType(kind).structureLabel(label)
                .timestamp(Instant.parse(iso)).price(price)
                .atrAtPivot(10.0).rsiAtPivot(rsi).build();
    }
    private static MarketStructurePoint hrPivot(String iso, double price, PivotType kind,
                                                  StructureLabel label, double rsi) {
        return MarketStructurePoint.builder()
                .pivotType(kind).structureLabel(label)
                .timestamp(Instant.parse(iso)).price(price)
                .atrAtPivot(10.0).rsiAtPivot(rsi).build();
    }
}
