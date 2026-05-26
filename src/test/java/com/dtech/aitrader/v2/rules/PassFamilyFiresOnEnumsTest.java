package com.dtech.aitrader.v2.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the multi-pass-engine vocabulary against SPEC-004 ({@code 4e185036}).
 *
 * <p>These three enums are the architectural contract every rule + the engine read; if a value
 * is renamed or its order changes silently, downstream rules break. This test fails loudly on
 * any drift, before any rule code runs.
 *
 * <p>Test-first per owner standing instruction (less hallucination — assertions match the spec
 * verbatim, not what the impl produced).
 */
class PassFamilyFiresOnEnumsTest {

    @Test
    void pass_values_and_order_match_spec() {
        // Spec 4e185036 PASS PIPELINE:
        // 0 CONTEXT_BUILD, 1 STRUCTURAL, 2 ENUMERATION, 3 VALIDATION,
        // 4 CLASSIFICATION, 5 CONFIRMATION, 6 SYNTHESIS
        assertEquals(0, Pass.P0_CONTEXT_BUILD.order);
        assertEquals(1, Pass.P1_STRUCTURAL.order);
        assertEquals(2, Pass.P2_ENUMERATION.order);
        assertEquals(3, Pass.P3_VALIDATION.order);
        assertEquals(4, Pass.P4_CLASSIFICATION.order);
        assertEquals(5, Pass.P5_CONFIRMATION.order);
        assertEquals(6, Pass.P6_SYNTHESIS.order);

        // Exactly 7 passes — adding one silently would be a spec change.
        assertEquals(7, Pass.values().length);

        // Order field must equal ordinal — used for fold-ordering and pass iteration.
        for (Pass p : Pass.values()) {
            assertEquals(p.ordinal(), p.order,
                    "Pass " + p.name() + " ordinal/order mismatch — fold ordering would break");
        }
    }

    @Test
    void family_values_match_spec() {
        // Spec 4e185036: family ∈ {EW, PATTERN, INDICATOR, STRUCTURE, SYNTHESIS}
        assertNotNull(Family.valueOf("EW"));
        assertNotNull(Family.valueOf("PATTERN"));
        assertNotNull(Family.valueOf("INDICATOR"));
        assertNotNull(Family.valueOf("STRUCTURE"));
        assertNotNull(Family.valueOf("SYNTHESIS"));
        assertEquals(5, Family.values().length);
    }

    @Test
    void firesOn_values_match_spec() {
        // Spec 4e185036: fires_on ∈ {FACT, CANDIDATE, ELIMINATION, CLASSIFICATION,
        //                            CONFIRMATION, VERDICT} — plus SPEC-006 addition WATCH
        // (1d3e3c25) for the in-progress thesis surface.
        assertNotNull(FiresOn.valueOf("FACT"));
        assertNotNull(FiresOn.valueOf("CANDIDATE"));
        assertNotNull(FiresOn.valueOf("ELIMINATION"));
        assertNotNull(FiresOn.valueOf("CLASSIFICATION"));
        assertNotNull(FiresOn.valueOf("CONFIRMATION"));
        assertNotNull(FiresOn.valueOf("VERDICT"));
        assertNotNull(FiresOn.valueOf("WATCH"));
        assertEquals(7, FiresOn.values().length);
    }

    @Test
    void firesOn_isOutcomeBearing_only_for_verdict() {
        // Per Q7 (b5c65d36) + SPEC-006 (1d3e3c25): only fires_on=VERDICT firings get outcome rows.
        // WATCH is explicitly non-outcome-bearing (highest-thesis observability without polluting
        // the eval contract). Encoded on the enum so callers cannot drift.
        assertTrue(FiresOn.VERDICT.isOutcomeBearing());
        for (FiresOn f : FiresOn.values()) {
            if (f != FiresOn.VERDICT) {
                assertFalse(f.isOutcomeBearing(),
                        f.name() + " must NOT be outcome-bearing (only VERDICT is)");
            }
        }
    }
}
