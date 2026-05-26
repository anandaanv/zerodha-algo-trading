package com.dtech.aitrader.v2.rules.ew.signature;

import com.dtech.aitrader.v2.rules.ew.signature.rules.BiggerImpulseSignatureRule;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BiggerImpulseSignatureRule — locks owner's worked example from the reframe ({@code 159ba913}):
 *
 * <ul>
 *   <li>Admitted while observed legs are consistent with ≥1 corrective signature (zigzag 5-3-5
 *       / flat 3-3-5 / triangle 3-3-3-3-3 at the observed positions).</li>
 *   <li>Invalidated by a determinate observed leg-character that NO corrective signature accepts
 *       at that position. Owner's exact wording: "a 3-5 where corrective is required kills it."</li>
 * </ul>
 */
class BiggerImpulseSignatureRuleTest {

    private final BiggerImpulseSignatureRule rule = new BiggerImpulseSignatureRule();

    @Test
    void admitted_when_position_0_FIVE_accepted_by_zigzag_signature() {
        // A observed FIVE → matches zigzag's position 0 (5-3-5) → admitted.
        List<ObservedLeg> observed = List.of(leg("A", LegCharacter.FIVE));
        AdmissionResult r = rule.evaluate(observed);
        assertEquals(AdmissionState.ADMITTED, r.state());
        assertEquals(1, r.matchedLegsCount());
    }

    @Test
    void admitted_when_position_0_THREE_accepted_by_flat_or_triangle() {
        // A observed THREE → matches flat (3-3-5) and triangle (3-3-3-3-3) at position 0.
        List<ObservedLeg> observed = List.of(leg("A", LegCharacter.THREE));
        AdmissionResult r = rule.evaluate(observed);
        assertEquals(AdmissionState.ADMITTED, r.state());
    }

    @Test
    void admitted_with_zigzag_AB_partial() {
        // A=FIVE, B=THREE → matches zigzag's 5-3-5 at positions 0,1.
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.FIVE),
                leg("B", LegCharacter.THREE),
                leg("C", LegCharacter.INDETERMINATE));
        AdmissionResult r = rule.evaluate(observed);
        assertEquals(AdmissionState.ADMITTED, r.state());
    }

    @Test
    void pending_when_all_indeterminate() {
        // No observed leg determinate → no possibility eliminated → PENDING (admissible
        // but not yet LIVE).
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.INDETERMINATE),
                leg("B", LegCharacter.INDETERMINATE),
                leg("C", LegCharacter.INDETERMINATE));
        AdmissionResult r = rule.evaluate(observed);
        assertEquals(AdmissionState.PENDING, r.state());
    }

    @Test
    void pending_when_empty_observed() {
        AdmissionResult r = rule.evaluate(List.of());
        assertEquals(AdmissionState.PENDING, r.state());
    }

    @Test
    void invalidated_when_position_4_observed_is_inconsistent_with_all_corrective_signatures() {
        // CORRECTIVE_SIGNATURES has the triangle's position 4 = THREE (the only signature with
        // position 4). If we observe E=FIVE, no corrective signature accepts FIVE at position 4
        // → INVALIDATED.
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.INDETERMINATE),
                leg("B", LegCharacter.INDETERMINATE),
                leg("C", LegCharacter.INDETERMINATE),
                leg("D", LegCharacter.INDETERMINATE),
                leg("E", LegCharacter.FIVE));
        AdmissionResult r = rule.evaluate(observed);
        assertEquals(AdmissionState.INVALIDATED, r.state(),
                "owner: 'a 3-5 where corrective is required kills it' — position 4 must be THREE for the only signature that has 5 legs");
        assertEquals("E", r.contradictingLeg());
    }

    @Test
    void admitted_when_position_2_matches_either_FIVE_or_FIVE() {
        // C position is FIVE for zigzag/flat AND THREE for triangle. C=FIVE accepted by
        // zigzag+flat; ADMITTED. (Triangle gets eliminated by position-0 mismatch separately.)
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.FIVE),
                leg("B", LegCharacter.THREE),
                leg("C", LegCharacter.FIVE));
        AdmissionResult r = rule.evaluate(observed);
        assertEquals(AdmissionState.ADMITTED, r.state());
    }

    @Test
    void derive_levels_returns_macro_anchor_as_watch_when_first_leg_complete() {
        // For an observed downside A leg (1611→1290), bigger-impulse implies an eventual UP move
        // after the correction resolves → watch = above A_start (1611), invalidation is
        // structural (not a price).
        List<ObservedLeg> observed = List.of(
                new ObservedLeg("A", "2025-12-31", 1611.8, "2026-04-01", 1290.0,
                        LegCharacter.FIVE, Map.of()));
        DerivedLevels lv = rule.deriveLevels(observed, null);
        assertEquals(1, lv.watch().size());
        assertEquals(1611.8, lv.watch().get(0).price(), 1e-6);
        assertTrue(lv.watch().get(0).label().toLowerCase().contains("above")
                        || lv.watch().get(0).label().toLowerCase().contains("a_start"),
                "watch label must reference A_start break direction; got " + lv.watch().get(0).label());
    }

    @Test
    void rule_id_and_form_name_stable() {
        // IDs are stable strings — referenced in firing payloads + future DSL declarations.
        assertEquals("bigger-impulse-composite", rule.id());
        assertEquals("bigger-impulse", rule.formName());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ObservedLeg leg(String label, LegCharacter c) {
        if (c == LegCharacter.INDETERMINATE) {
            return new ObservedLeg(label, null, 0.0, null, null, c, Map.of());
        }
        return new ObservedLeg(label, "2024-01-01", 100.0, "2024-02-01", 110.0, c, Map.of());
    }
}
