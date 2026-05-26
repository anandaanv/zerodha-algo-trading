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
 * EwAnnotationPriorRule — spec-derived test.
 *
 * <p><b>Requirement source:</b>
 * <ul>
 *   <li>SPEC-005 ({@code ab9bd541}): "if a candidate aligns with a weight≥2 annotation FACT,
 *       GRADUATED boost per the annotation weight (Rule 0.35 / 0.7). (RELIANCE: MF1 corrective
 *       aligns with the 'wave 4C' annotation → boost.)"</li>
 *   <li>BLESSED cde6bbc9 ({@code 48130d04}): weight-3 owner annotation "On weekly, the stock
 *       appears to be in wave 4C" should match the corrective MF1 (zigzag) framing and NOT match
 *       the impulsive MF2 framing.</li>
 *   <li>Convergence Q5 ({@code 9c60e777}): one firing per (candidate, annotation) — locked.</li>
 * </ul>
 *
 * <p><b>Test contract (independent of current code):</b>
 * Each test asserts behavior the spec promises. Mismatches with current implementation are bugs
 * to investigate, not test expectations to soften. The "wave 4C" string and weight-3 are the
 * exact owner-provided values from {@code 48130d04}.
 */
class EwAnnotationPriorRuleTest {

    private final EwAnnotationPriorRule rule = new EwAnnotationPriorRule();

    @Test
    void blessed_wave_4c_annotation_matches_zigzag_emits_graduated_boost() {
        // The owner's verbatim weight-3 annotation from dffe1f75. Spec says corrective forms
        // (zigzag/flat/triangle/...) match this; impulse does not.
        Firing zigzag = zigzagCandidate("mf1");
        Firing annotation = annotationFact("ann-5",
                "On weekly, the stock appears to be in wave 4C. now we dont know from the context whether we are in 2of C or 4 of C..",
                3);

        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(annotation, zigzag));
        assertEquals(1, emitted.size(),
                "weight-3 wave-4C annotation must produce exactly one boost on MF1 zigzag");
        Firing f = emitted.get(0);

        assertEquals(EwAnnotationPriorRule.RULE_ID, f.getRuleId());
        assertEquals(Pass.P4_CLASSIFICATION, f.getPass());
        assertEquals(FiresOn.CLASSIFICATION, f.getFiresOn());
        assertEquals(Family.EW, f.getFamily());
        assertEquals(List.of("mf1"), f.getRefs());
        assertEquals(PriorDelta.Kind.GRADUATED, f.getPriorDelta().kind(),
                "spec mandates GRADUATED per-weight boost — not categorical");
        assertTrue(f.getPriorDelta().graduatedDelta() > 0.0,
                "matching corrective annotation must be a POSITIVE prior bump");
    }

    @Test
    void blessed_wave_4c_annotation_does_not_match_impulse() {
        // Per spec: an annotation describing a corrective wave (4C) must NOT boost an impulse
        // framing. This is the key discriminator that lets the annotation push MF1 above MF2 in
        // the blessed reference.
        Firing impulse = impulseCandidate("mf2");
        Firing annotation = annotationFact("ann-5",
                "On weekly, the stock appears to be in wave 4C.",
                3);

        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(annotation, impulse));
        assertTrue(emitted.isEmpty(),
                "corrective-language annotation must NOT match impulse candidates");
    }

    @Test
    void wave_4c_picks_zigzag_only_when_both_candidates_present() {
        // The discriminating case from the blessed reference: both MF1 zigzag and MF2 impulse
        // are present; only MF1 gets the boost.
        Firing zigzag = zigzagCandidate("mf1");
        Firing impulse = impulseCandidate("mf2");
        Firing annotation = annotationFact("ann-5", "wave 4C correction", 3);

        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(annotation, zigzag, impulse));
        assertEquals(1, emitted.size(), "exactly one boost — only the corrective candidate matches");
        assertEquals(List.of("mf1"), emitted.get(0).getRefs(),
                "the boost must reference MF1 zigzag, not MF2 impulse");
    }

    @Test
    void impulse_keyword_annotation_matches_impulse_candidate() {
        // Symmetric case: an annotation saying "wave 3 impulse" should match impulse candidates.
        // Locks the keyword-matching direction so future hot-fixes can't inadvertently make
        // ALL annotations match ALL candidates.
        Firing impulse = impulseCandidate("mf2");
        Firing annotation = annotationFact("ann-imp", "Looks like wave 3 of a new impulse up", 3);

        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(annotation, impulse));
        assertEquals(1, emitted.size(),
                "impulse-language annotation must match impulse candidate");
        assertTrue(emitted.get(0).getPriorDelta().graduatedDelta() > 0.0);
    }

    @Test
    void impulse_keyword_annotation_does_not_match_zigzag() {
        // Mirror: impulse language must NOT boost corrective candidates.
        Firing zigzag = zigzagCandidate("mf1");
        Firing annotation = annotationFact("ann-imp", "Looks like wave 3 of a new impulse up", 3);

        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(annotation, zigzag));
        assertTrue(emitted.isEmpty(),
                "impulse-language annotation must NOT match corrective candidates");
    }

    @Test
    void weight_scales_delta_higher_weight_means_bigger_boost() {
        // Spec: "GRADUATED boost PER THE ANNOTATION WEIGHT" — weight should modulate magnitude.
        // Two identical annotations differing only by weight should produce ordered deltas.
        Firing zigzag1 = zigzagCandidate("zz1");
        Firing zigzag2 = zigzagCandidate("zz2");
        Firing weight3Ann = annotationFact("ann-w3", "wave 4 correction", 3);
        Firing weight2Ann = annotationFact("ann-w2", "wave 4 correction", 2);

        List<Firing> w3Emitted = rule.evaluate(blankCtx(), List.of(weight3Ann, zigzag1));
        List<Firing> w2Emitted = rule.evaluate(blankCtx(), List.of(weight2Ann, zigzag2));
        assertEquals(1, w3Emitted.size());
        assertEquals(1, w2Emitted.size());
        assertTrue(
                w3Emitted.get(0).getPriorDelta().graduatedDelta()
                        > w2Emitted.get(0).getPriorDelta().graduatedDelta(),
                "weight-3 boost must exceed weight-2 boost (delta scales with weight)");
    }

    @Test
    void no_annotation_facts_no_firings() {
        Firing zigzag = zigzagCandidate("mf1");
        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(zigzag));
        assertTrue(emitted.isEmpty(),
                "no annotation FACTs ⇒ no boosts (rule is gated on Pass-1 annotation intake)");
    }

    @Test
    void eliminated_candidates_skipped() {
        Firing zigzag = zigzagCandidate("mf1");
        Firing annotation = annotationFact("ann-5", "wave 4C correction", 3);
        Firing elim = Firing.builder()
                .id("elim-1").ruleId(EwHardRuleValidatorRule.RULE_ID).family(Family.EW)
                .pass(Pass.P3_VALIDATION).firesOn(FiresOn.ELIMINATION)
                .refs(List.of("mf1"))
                .priorDelta(PriorDelta.eliminate("test", "3"))
                .build();
        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(annotation, zigzag, elim));
        assertTrue(emitted.isEmpty(),
                "eliminated candidates skip Pass-4 annotation-prior boosts");
    }

    @Test
    void unrelated_annotation_text_no_firings() {
        // An annotation with no wave-related keywords (just a generic level note) must not
        // produce false matches.
        Firing zigzag = zigzagCandidate("mf1");
        Firing irrelevant = annotationFact("ann-x",
                "Stock seems to be respecting this level lately, watch it", 3);
        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(irrelevant, zigzag));
        assertTrue(emitted.isEmpty(),
                "no EW-form keyword in annotation text ⇒ no boost");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static SymbolContext blankCtx() {
        return SymbolContext.builder()
                .symbol("RELIANCE").tf("Week").asOf(LocalDate.of(2026, 5, 22)).build();
    }

    private static Firing zigzagCandidate(String id) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        return Firing.builder()
                .id(id).ruleId(EwEnumerationRule.RULE_ID).family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.45).payload(p).build();
    }

    private static Firing impulseCandidate(String id) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "impulse");
        return Firing.builder()
                .id(id).ruleId(EwEnumerationRule.RULE_ID).family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.25).payload(p).build();
    }

    private static Firing annotationFact(String id, String text, int weight) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("text", text);
        p.put("weight", weight);
        return Firing.builder()
                .id(id).ruleId(EwAnnotationIntakeRule.RULE_ID).family(Family.EW)
                .pass(Pass.P1_STRUCTURAL).firesOn(FiresOn.FACT)
                .payload(p).build();
    }
}
