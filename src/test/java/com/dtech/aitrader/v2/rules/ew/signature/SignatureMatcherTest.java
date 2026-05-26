package com.dtech.aitrader.v2.rules.ew.signature;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SignatureMatcher — locks the generic partial-match algorithm per SPEC reframe ({@code 159ba913}).
 *
 * <p><b>Contract (from the reframe):</b>
 * <ul>
 *   <li>observed determinate matches expected → counts as matched</li>
 *   <li>observed determinate ≠ expected → CONTRADICTION → {@link AdmissionState#INVALIDATED}</li>
 *   <li>observed INDETERMINATE → pending; doesn't contribute to match or contradiction</li>
 *   <li>≥1 match, no contradictions → {@link AdmissionState#ADMITTED} (possibly partial)</li>
 *   <li>zero matches, no contradictions → {@link AdmissionState#PENDING}</li>
 *   <li>contradiction stops the scan and locks INVALIDATED with the contradicting leg recorded</li>
 * </ul>
 */
class SignatureMatcherTest {

    @Test
    void all_legs_match_admitted() {
        Signature sig = new Signature("zigzag", List.of("A", "B", "C"),
                List.of(LegCharacter.FIVE, LegCharacter.THREE, LegCharacter.FIVE));
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.FIVE),
                leg("B", LegCharacter.THREE),
                leg("C", LegCharacter.FIVE));

        AdmissionResult r = SignatureMatcher.match(sig, observed);
        assertEquals(AdmissionState.ADMITTED, r.state());
        assertEquals(3, r.matchedLegsCount());
        assertNull(r.contradictingLeg());
    }

    @Test
    void partial_match_with_trailing_indeterminate_admitted() {
        // First 2 legs determinate + match; 3rd is INDETERMINATE (incomplete leg) → ADMITTED
        // (partial) per reframe — admissible because prior structure qualifies; pending
        // confirmation.
        Signature sig = new Signature("zigzag", List.of("A", "B", "C"),
                List.of(LegCharacter.FIVE, LegCharacter.THREE, LegCharacter.FIVE));
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.FIVE),
                leg("B", LegCharacter.THREE),
                leg("C", LegCharacter.INDETERMINATE));

        AdmissionResult r = SignatureMatcher.match(sig, observed);
        assertEquals(AdmissionState.ADMITTED, r.state());
        assertEquals(2, r.matchedLegsCount(),
                "two legs matched; third is pending — partial match counts as ADMITTED");
    }

    @Test
    void determinate_contradiction_invalidates_locking_the_leg() {
        // B observed as FIVE but zigzag signature requires THREE → INVALIDATED. The matcher
        // records which leg contradicted (essential for the level-map's "why_eliminated" UX).
        Signature sig = new Signature("zigzag", List.of("A", "B", "C"),
                List.of(LegCharacter.FIVE, LegCharacter.THREE, LegCharacter.FIVE));
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.FIVE),
                leg("B", LegCharacter.FIVE),     // contradicts THREE
                leg("C", LegCharacter.INDETERMINATE));

        AdmissionResult r = SignatureMatcher.match(sig, observed);
        assertEquals(AdmissionState.INVALIDATED, r.state());
        assertEquals("B", r.contradictingLeg(),
                "contradicting leg label must be surfaced for audit");
        assertEquals("FIVE", r.evidence().get("observed"));
        assertEquals("THREE", r.evidence().get("expected"));
    }

    @Test
    void all_indeterminate_pending() {
        // No leg determinate yet — the form is admissible (no contradiction) but not yet alive.
        Signature sig = new Signature("triangle", List.of("A", "B", "C", "D", "E"),
                List.of(LegCharacter.THREE, LegCharacter.THREE, LegCharacter.THREE,
                        LegCharacter.THREE, LegCharacter.THREE));
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.INDETERMINATE),
                leg("B", LegCharacter.INDETERMINATE),
                leg("C", LegCharacter.INDETERMINATE),
                leg("D", LegCharacter.INDETERMINATE),
                leg("E", LegCharacter.INDETERMINATE));

        AdmissionResult r = SignatureMatcher.match(sig, observed);
        assertEquals(AdmissionState.PENDING, r.state());
        assertEquals(0, r.matchedLegsCount());
    }

    @Test
    void first_contradiction_stops_scan() {
        // Even if subsequent legs would match, the first determinate contradiction wins.
        Signature sig = new Signature("triangle", List.of("A", "B", "C", "D", "E"),
                List.of(LegCharacter.THREE, LegCharacter.THREE, LegCharacter.THREE,
                        LegCharacter.THREE, LegCharacter.THREE));
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.FIVE),     // contradicts at A — should NOT proceed to B+
                leg("B", LegCharacter.THREE),
                leg("C", LegCharacter.THREE),
                leg("D", LegCharacter.INDETERMINATE),
                leg("E", LegCharacter.INDETERMINATE));

        AdmissionResult r = SignatureMatcher.match(sig, observed);
        assertEquals(AdmissionState.INVALIDATED, r.state());
        assertEquals("A", r.contradictingLeg());
    }

    @Test
    void observed_size_mismatch_throws() {
        // Caller responsibility — orchestrator pads observed to signature length; if it doesn't,
        // matcher fails loudly rather than silently mis-matching positions.
        Signature sig = new Signature("zigzag", List.of("A", "B", "C"),
                List.of(LegCharacter.FIVE, LegCharacter.THREE, LegCharacter.FIVE));
        List<ObservedLeg> observed = List.of(
                leg("A", LegCharacter.FIVE),
                leg("B", LegCharacter.THREE));    // only 2; signature has 3

        assertThrows(IllegalArgumentException.class,
                () -> SignatureMatcher.match(sig, observed));
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static ObservedLeg leg(String label, LegCharacter c) {
        return new ObservedLeg(label, "2024-01-01", 100.0, "2024-02-01", 110.0, c, Map.of());
    }
}
