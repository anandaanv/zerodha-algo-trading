package com.dtech.aitrader.v2.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks {@link PriorFold#foldChain(Firing, List)} — the candidate-chain walk that derives a
 * candidate's live prior from the firings that reference it.
 *
 * <p>Combines: refs filtering + (round, pass, id) sort + the discriminated-union fold logic that
 * {@link PriorFoldTest} already covers in isolation.
 */
class PriorFoldChainTest {

    @Test
    void empty_chain_returns_basePrior_alive() {
        Firing candidate = candidate("c1", 0.40);
        PriorFold.Result r = PriorFold.foldChain(candidate, List.of(candidate));
        assertEquals(0.40, r.livePrior(), 1e-9);
        assertFalse(r.eliminated());
    }

    @Test
    void missing_basePrior_defaults_to_half() {
        // Per spec: a candidate without a basePrior gets 0.5 — visible but conservative.
        Firing candidate = Firing.builder()
                .id("c1").ruleId("CAND").pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .build();
        PriorFold.Result r = PriorFold.foldChain(candidate, List.of(candidate));
        assertEquals(0.5, r.livePrior(), 1e-9);
    }

    @Test
    void chain_applies_deltas_in_pass_order() {
        Firing cand = candidate("c1", 0.40);

        // Pass 4 classification: floor at 0.50
        Firing f4 = firingRefing("F4", "c1", Pass.P4_CLASSIFICATION,
                PriorDelta.floorSet(0.50, "x", "0.65"));
        // Pass 3 elimination ? no — eliminate sticks, want graduated here
        Firing f3 = firingRefing("F3", "c1", Pass.P3_VALIDATION,
                PriorDelta.graduated(-0.10, "weak", "0.95"));

        // Sorted P3 then P4: start 0.40 → 0.30 → floor 0.50 → final 0.50.
        PriorFold.Result r = PriorFold.foldChain(cand, List.of(cand, f4, f3));
        assertEquals(0.50, r.livePrior(), 1e-9);
        assertFalse(r.eliminated());
    }

    @Test
    void chain_eliminates_when_categorical_present() {
        Firing cand = candidate("c1", 0.60);
        Firing kill = firingRefing("F3", "c1", Pass.P3_VALIDATION,
                PriorDelta.eliminate("Rule 0.96", "0.96"));
        Firing later = firingRefing("F5", "c1", Pass.P5_CONFIRMATION,
                PriorDelta.graduated(+0.30, "spurious", "0.95"));

        PriorFold.Result r = PriorFold.foldChain(cand, List.of(cand, kill, later));
        assertTrue(r.eliminated());
        assertEquals(0.0, r.livePrior(), 1e-9);
    }

    @Test
    void only_firings_refing_this_candidate_are_folded() {
        // Two candidates; firings referencing one MUST NOT affect the other.
        Firing candA = candidate("cA", 0.40);
        Firing candB = candidate("cB", 0.40);
        Firing affectsA = firingRefing("F1", "cA", Pass.P4_CLASSIFICATION,
                PriorDelta.graduated(+0.30, "promote A", "0.65"));
        Firing affectsB = firingRefing("F2", "cB", Pass.P4_CLASSIFICATION,
                PriorDelta.graduated(-0.20, "demote B", "0.95"));

        PriorFold.Result rA = PriorFold.foldChain(candA, List.of(candA, candB, affectsA, affectsB));
        PriorFold.Result rB = PriorFold.foldChain(candB, List.of(candA, candB, affectsA, affectsB));

        assertEquals(0.70, rA.livePrior(), 1e-9);
        assertEquals(0.20, rB.livePrior(), 1e-9);
    }

    // ──────────────────────────────────────────────────────────────────────────

    private static Firing candidate(String id, double basePrior) {
        return Firing.builder()
                .id(id).ruleId("CAND-" + id).pass(Pass.P2_ENUMERATION).firesOn(FiresOn.CANDIDATE)
                .basePrior(basePrior).build();
    }

    private static Firing firingRefing(String id, String candId, Pass p, PriorDelta delta) {
        return Firing.builder()
                .id(id).ruleId("R-" + id).pass(p)
                .firesOn(p == Pass.P3_VALIDATION ? FiresOn.ELIMINATION : FiresOn.CLASSIFICATION)
                .refs(List.of(candId)).priorDelta(delta).build();
    }
}
