package com.dtech.aitrader.v2.rules.synthesis;

import com.dtech.aitrader.data.RuleFiring;
import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.Pass;
import com.dtech.aitrader.v2.rules.PriorDelta;
import com.dtech.aitrader.v2.rules.SymbolContext;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * EwVerdictSynthesisRule — locks the SPEC-reframe ({@code 159ba913}) decision tree:
 *
 * <ol>
 *   <li>Collect signature firings with state=ADMITTED, dedupe by formName, cap at maxLive.</li>
 *   <li>If exactly ONE live hypothesis AND its candidate's gate CONFIRMED AND wave-state COMPLETE
 *       → emit VERDICT.</li>
 *   <li>Otherwise → emit WATCH with hypotheses_live carrying watch+invalidation levels.</li>
 * </ol>
 *
 * <p>The old prior-ranking model is retired (parked as {@code .legacy} pre-rewrite). These
 * tests cover the new model only.
 */
class EwVerdictSynthesisRuleTest {

    private final EwVerdictSynthesisRule rule = new EwVerdictSynthesisRule();

    @Test
    void single_live_with_gate_confirmed_and_complete_emits_verdict() {
        // The narrow VERDICT path: exactly one ADMITTED hypothesis, gate CONFIRMED, terminal
        // wave state COMPLETE. Engine commits to a tradable verdict.
        Firing cand = zigzagCandidate("c1", 1611.8, 1290.0, 1473.4);
        Firing sig = admittedSignatureFiring("s1", "c1", "zigzag", "zigzag-5-3-5");
        Firing gate = gateFiring("g1", "c1", "CONFIRMED");
        Firing wave = waveCompletionFiring("w1", "c1", "B_end", "COMPLETE");

        List<Firing> emitted = rule.evaluate(blankCtx(), List.of(cand, sig, gate, wave));
        assertEquals(1, emitted.size());
        Firing v = emitted.get(0);
        assertEquals(FiresOn.VERDICT, v.getFiresOn(),
                "single-live + gate CONFIRMED + state COMPLETE ⇒ VERDICT path");
        assertEquals("zigzag", v.getPayload().get("winning_form"));
        assertEquals(RuleFiring.Bias.SHORT, v.getBias());
    }

    @Test
    void multiple_live_emits_watch_with_level_map_even_when_gate_confirmed() {
        // SPEC reframe: cap ~2-3 live hypotheses is normal. With ≥2 live, engine MUST emit WATCH
        // (no single thesis is yet "the" verdict). TCS-style: gate CONFIRMED + state COMPLETE
        // but still multi-live ⇒ WATCH.
        Firing cand = zigzagCandidate("c1", 4592.25, 2346.2, 2614.0);
        Firing sig1 = admittedSignatureFiring("s1", "c1", "zigzag", "zigzag-5-3-5");
        Firing sig2 = admittedSignatureFiring("s2", "c1", "truncated-c", "truncated-c-5-3-5");
        Firing gate = gateFiring("g1", "c1", "CONFIRMED");
        Firing wave = waveCompletionFiring("w1", "c1", "B_end", "COMPLETE");

        Firing f = rule.evaluate(blankCtx(), List.of(cand, sig1, sig2, gate, wave)).get(0);
        assertEquals(FiresOn.WATCH, f.getFiresOn(),
                "≥2 live hypotheses ⇒ WATCH even with gate CONFIRMED");
        assertEquals(2, ((Number) f.getPayload().get("live_hypotheses_count")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hyps =
                (List<Map<String, Object>>) f.getPayload().get("hypotheses_live");
        assertNotNull(hyps);
        assertEquals(2, hyps.size());
    }

    @Test
    void gate_unconfirmed_emits_watch() {
        // RELIANCE-style: single hypothesis admitted, but price hasn't broken the reference low →
        // gate UNCONFIRMED → no VERDICT, emit WATCH.
        Firing cand = zigzagCandidate("c1", 1611.8, 1290.0, 1473.4);
        Firing sig = admittedSignatureFiring("s1", "c1", "zigzag", "zigzag-5-3-5");
        Firing gate = gateFiring("g1", "c1", "UNCONFIRMED");
        Firing wave = waveCompletionFiring("w1", "c1", "B_end", "COMPLETE");

        Firing f = rule.evaluate(blankCtx(), List.of(cand, sig, gate, wave)).get(0);
        assertEquals(FiresOn.WATCH, f.getFiresOn());
        assertEquals("UNCONFIRMED", f.getPayload().get("gate_state"));
    }

    @Test
    void wave_in_progress_emits_watch() {
        // ICICIBANK-style: terminal wave IN_PROGRESS (per SPEC-006). Even with gate CONFIRMED
        // and single hypothesis, the wave isn't done — emit WATCH.
        Firing cand = zigzagCandidate("c1", 1500.0, 1187.6, 1393.1);
        Firing sig = admittedSignatureFiring("s1", "c1", "zigzag", "zigzag-5-3-5");
        Firing gate = gateFiring("g1", "c1", "CONFIRMED");
        Firing wave = waveCompletionFiring("w1", "c1", "B_end", "IN_PROGRESS");

        Firing f = rule.evaluate(blankCtx(), List.of(cand, sig, gate, wave)).get(0);
        assertEquals(FiresOn.WATCH, f.getFiresOn());
        assertEquals("IN_PROGRESS", f.getPayload().get("terminal_wave_state"));
    }

    @Test
    void no_admitted_hypotheses_emits_nothing() {
        // If all signature firings are INVALIDATED or PENDING, no hypothesis is LIVE — engine
        // returns empty (no firing).
        Firing cand = zigzagCandidate("c1", 1611.8, 1290.0, 1473.4);
        Firing sigInvalidated = signatureFiring("s1", "c1", "flat", "flat-3-3-5", "INVALIDATED");
        Firing sigPending = signatureFiring("s2", "c1", "impulse", "impulse-5-3-5-3-5", "PENDING");

        assertTrue(rule.evaluate(blankCtx(), List.of(cand, sigInvalidated, sigPending)).isEmpty(),
                "no admitted hypotheses ⇒ no firing");
    }

    @Test
    void formName_dedupe_keeps_one_per_form() {
        // Two ADMITTED firings for the same form (e.g. two candidates each admitted as zigzag) —
        // the live set dedupes by formName, surfaces only ONE zigzag hypothesis.
        Firing cand1 = zigzagCandidate("c1", 1611.8, 1290.0, 1473.4);
        Firing cand2 = zigzagCandidate("c2", 1611.8, 1290.0, 1473.4);
        Firing sig1 = admittedSignatureFiring("s1", "c1", "zigzag", "zigzag-5-3-5");
        Firing sig2 = admittedSignatureFiring("s2", "c2", "zigzag", "zigzag-5-3-5");
        Firing gate = gateFiring("g1", "c1", "UNCONFIRMED");

        Firing f = rule.evaluate(blankCtx(), List.of(cand1, cand2, sig1, sig2, gate)).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hyps =
                (List<Map<String, Object>>) f.getPayload().get("hypotheses_live");
        assertEquals(1, hyps.size(),
                "two ADMITTED firings of same form should dedupe to 1 live hypothesis");
    }

    @Test
    void cap_live_set_at_max_live() {
        // ≥4 admitted forms: engine caps at maxLive=3 per SPEC reframe ("more = chaos").
        Firing cand = zigzagCandidate("c1", 1611.8, 1290.0, 1473.4);
        Firing s1 = admittedSignatureFiring("s1", "c1", "zigzag", "zigzag-5-3-5");
        Firing s2 = admittedSignatureFiring("s2", "c1", "flat", "flat-3-3-5");
        Firing s3 = admittedSignatureFiring("s3", "c1", "triangle", "triangle-3-3-3-3-3");
        Firing s4 = admittedSignatureFiring("s4", "c1", "truncated-c", "truncated-c-5-3-5");
        Firing s5 = admittedSignatureFiring("s5", "c1", "bigger-impulse", "bigger-impulse-composite");

        Firing f = rule.evaluate(blankCtx(), List.of(cand, s1, s2, s3, s4, s5)).get(0);
        int liveCount = ((Number) f.getPayload().get("live_hypotheses_count")).intValue();
        assertTrue(liveCount <= 3, "live set must be capped at maxLive=3; got " + liveCount);
    }

    @Test
    void watch_payload_carries_full_level_map_per_hypothesis() {
        // Each live hypothesis surfaces its derived watch + invalidation levels (the level-map).
        Firing cand = zigzagCandidate("c1", 1611.8, 1290.0, 1473.4);
        Map<String, Object> sigPayload = baseSignaturePayload("c1", "zigzag", "zigzag-5-3-5", "ADMITTED");
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("watch", List.of(Map.of("price", 1151.6, "label", "C target", "basis", "B−|A|")));
        derived.put("invalidation", List.of(Map.of("price", 1611.8, "label", "above A_start", "basis", "macro origin")));
        sigPayload.put("derived_levels", derived);
        Firing sig = customSignatureFiring("s1", "c1", sigPayload);
        Firing gate = gateFiring("g1", "c1", "UNCONFIRMED");

        Firing f = rule.evaluate(blankCtx(), List.of(cand, sig, gate)).get(0);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> hyps =
                (List<Map<String, Object>>) f.getPayload().get("hypotheses_live");
        assertEquals(1, hyps.size());
        Map<String, Object> h = hyps.get(0);
        assertEquals("zigzag", h.get("form"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> watch = (List<Map<String, Object>>) h.get("watch_levels");
        assertNotNull(watch);
        assertFalse(watch.isEmpty(), "watch levels must be surfaced");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> inval = (List<Map<String, Object>>) h.get("invalidation_levels");
        assertFalse(inval.isEmpty(), "invalidation levels must be surfaced");
    }

    @Test
    void watch_firing_is_neutral_bias_not_outcome_bearing() {
        // WATCH is the engine's "I see this, not yet tradable" surface. Bias must be NEUTRAL,
        // levels must be null (not actionable). The eval scorer never reads WATCH (Q7 contract).
        Firing cand = zigzagCandidate("c1", 1611.8, 1290.0, 1473.4);
        Firing sig = admittedSignatureFiring("s1", "c1", "zigzag", "zigzag-5-3-5");
        Firing gate = gateFiring("g1", "c1", "UNCONFIRMED");

        Firing f = rule.evaluate(blankCtx(), List.of(cand, sig, gate)).get(0);
        assertEquals(FiresOn.WATCH, f.getFiresOn());
        assertFalse(f.getFiresOn().isOutcomeBearing(),
                "WATCH must NOT be outcome-bearing — Q7 + SPEC-006 contract");
        assertEquals(RuleFiring.Bias.NEUTRAL, f.getBias());
        assertNull(f.getTriggerPrice());
        assertNull(f.getInvalidationPrice());
        assertNull(f.getTargetPrice());
    }

    @Test
    void verdict_signature_prefix_distinguishes_from_watch() {
        // contextSignature: VERDICT = "EW_<FORM>_VERDICT", WATCH = "WATCHING_EW_LEVEL_MAP_<n>_LIVE".
        // Eval analytics can group by this prefix without ambiguity.
        Firing cand = zigzagCandidate("c1", 1611.8, 1290.0, 1473.4);
        Firing sig = admittedSignatureFiring("s1", "c1", "zigzag", "zigzag-5-3-5");
        Firing gate = gateFiring("g1", "c1", "CONFIRMED");
        Firing wave = waveCompletionFiring("w1", "c1", "B_end", "COMPLETE");

        Firing verdict = rule.evaluate(blankCtx(), List.of(cand, sig, gate, wave)).get(0);
        assertTrue(verdict.getContextSignature().contains("EW_ZIGZAG_VERDICT"),
                "VERDICT signature: " + verdict.getContextSignature());

        Firing watchCand = zigzagCandidate("c2", 1611.8, 1290.0, 1473.4);
        Firing watchSig = admittedSignatureFiring("s2", "c2", "zigzag", "zigzag-5-3-5");
        Firing watchGate = gateFiring("g2", "c2", "UNCONFIRMED");
        Firing watch = rule.evaluate(blankCtx(), List.of(watchCand, watchSig, watchGate)).get(0);
        assertTrue(watch.getContextSignature().startsWith("WATCHING_EW_LEVEL_MAP"),
                "WATCH signature: " + watch.getContextSignature());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static SymbolContext blankCtx() {
        return SymbolContext.builder().symbol("RELIANCE").tf("Week")
                .asOf(LocalDate.of(2026, 5, 25)).build();
    }

    private static Firing zigzagCandidate(String id, double aStart, double aEnd, double bEnd) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("form", "zigzag");
        p.put("pivot_assignment", List.of(
                role("A_start", aStart, "COMPLETE"),
                role("A_end", aEnd, "COMPLETE"),
                role("B_end", bEnd, "CANDIDATE")));
        return Firing.builder()
                .id(id).ruleId("EW_ENUMERATION").family(Family.EW)
                .pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(0.45).payload(p).build();
    }

    private static Firing admittedSignatureFiring(String id, String candId,
                                                    String formName, String ruleId) {
        return signatureFiring(id, candId, formName, ruleId, "ADMITTED");
    }

    private static Firing signatureFiring(String id, String candId, String formName,
                                            String ruleId, String state) {
        return customSignatureFiring(id, candId, baseSignaturePayload(candId, formName, ruleId, state));
    }

    private static Map<String, Object> baseSignaturePayload(String candId, String formName,
                                                              String ruleId, String state) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("signature_rule_id", ruleId);
        p.put("form_name", formName);
        p.put("admission_state", state);
        p.put("matched_legs_count", "ADMITTED".equals(state) ? 2 : 0);
        p.put("reasoning", state + " in test");
        Map<String, Object> derived = new LinkedHashMap<>();
        derived.put("watch", new ArrayList<>());
        derived.put("invalidation", new ArrayList<>());
        p.put("derived_levels", derived);
        p.put("provisional", true);
        return p;
    }

    private static Firing customSignatureFiring(String id, String candId, Map<String, Object> payload) {
        return Firing.builder()
                .id(id).ruleId("EW_SIGNATURE_EVALUATION").family(Family.EW)
                .pass(Pass.P5_CONFIRMATION).firesOn(FiresOn.CONFIRMATION)
                .refs(List.of(candId))
                .priorDelta(PriorDelta.graduated(0.0, "test", "test"))
                .payload(payload).build();
    }

    private static Firing gateFiring(String id, String candId, String state) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("gate_state", state);
        p.put("thesis_direction", "SHORT");
        p.put("reference_role", "A_end");
        p.put("reference_level", 1290.0);
        p.put("current_price", 1318.7);
        return Firing.builder()
                .id(id).ruleId("EW_PRE_CONCLUSION_GATE").family(Family.EW)
                .pass(Pass.P5_CONFIRMATION).firesOn(FiresOn.CONFIRMATION)
                .refs(List.of(candId))
                .priorDelta(PriorDelta.graduated(0.0, "test", "test"))
                .payload(p).build();
    }

    private static Firing waveCompletionFiring(String id, String candId, String role, String newState) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("role", role);
        p.put("new_state", newState);
        p.put("candidate_form", "zigzag");
        return Firing.builder()
                .id(id).ruleId("EW_WAVE_COMPLETION").family(Family.EW)
                .pass(Pass.P5_CONFIRMATION).firesOn(FiresOn.CONFIRMATION)
                .refs(List.of(candId))
                .priorDelta(PriorDelta.graduated(0.0, "test", "test"))
                .payload(p).build();
    }

    private static Map<String, Object> role(String name, double price, String state) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", name);
        m.put("price", price);
        m.put("date", null);
        m.put("state", state);
        return m;
    }
}
