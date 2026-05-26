package com.dtech.aitrader.v2.rules;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Per Q2 override in the convergence memo ({@code 9c60e777}): spawned candidates from the
 * feedback loop declare SAME_ANCHOR or RE_ANCHOR; the engine reads {@code entryPass} to know
 * where the next round picks up. This test locks the entry-pass mapping.
 */
class SpawnAnchorModeTest {

    @Test
    void same_anchor_entry_is_pass3() {
        assertEquals(Pass.P3_VALIDATION, SpawnAnchorMode.SAME_ANCHOR.entryPass);
    }

    @Test
    void re_anchor_entry_is_pass1() {
        // Owner's Q2 override — a re-anchoring needs scoped Pass-1 recompute,
        // not just relabeling-in-place at Pass 3.
        assertEquals(Pass.P1_STRUCTURAL, SpawnAnchorMode.RE_ANCHOR.entryPass);
    }

    @Test
    void exactly_two_modes() {
        // Adding a third spawn mode would be a meaningful architectural decision —
        // fail loud if someone adds one silently.
        assertEquals(2, SpawnAnchorMode.values().length);
    }
}
