package com.dtech.aitrader.v2.rules;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Locks the prior-fold semantics against the convergence memo ({@code 9c60e777}) — specifically
 * Q4 (fold order) and Q5 (the GRADUATED / CATEGORICAL_ELIMINATE / FLOOR_SET discriminated union).
 *
 * <p>Test-first per owner standing instruction. Every behaviour that the engine relies on must
 * have a test here; if a future delta type breaks an assumption, the test fires.
 *
 * <p>The fold is the heart of the multi-pass engine's replayability claim: change weights and
 * re-fold without re-firing. If the fold is non-deterministic or order-sensitive in surprising
 * ways, that claim collapses. These tests are the guard.
 */
class PriorFoldTest {

    // ───────────── factory / shape ─────────────────────────────────────────────

    @Test
    void graduated_factory_carries_delta() {
        PriorDelta d = PriorDelta.graduated(-0.15, "Rule 0.95 leg-3 corrective", "0.95");
        assertEquals(PriorDelta.Kind.GRADUATED, d.kind());
        assertEquals(-0.15, d.graduatedDelta(), 1e-9);
        assertNull(d.floorValue());
        assertEquals("0.95", d.ruleRef());
    }

    @Test
    void categorical_eliminate_carries_no_delta_or_floor() {
        PriorDelta d = PriorDelta.eliminate("Rule 0.96 B-as-5-wave eliminates ABC", "0.96");
        assertEquals(PriorDelta.Kind.CATEGORICAL_ELIMINATE, d.kind());
        assertNull(d.graduatedDelta());
        assertNull(d.floorValue());
    }

    @Test
    void floor_set_carries_floor_value() {
        PriorDelta d = PriorDelta.floorSet(0.30, "Rule 0.5 alternative", "0.5");
        assertEquals(PriorDelta.Kind.FLOOR_SET, d.kind());
        assertEquals(0.30, d.floorValue(), 1e-9);
        assertNull(d.graduatedDelta());
    }

    // ───────────── empty chain ─────────────────────────────────────────────────

    @Test
    void empty_chain_returns_base_prior_alive() {
        PriorFold.Result r = PriorFold.fold(0.40, List.of());
        assertEquals(0.40, r.livePrior(), 1e-9);
        assertFalse(r.eliminated());
    }

    // ───────────── GRADUATED ───────────────────────────────────────────────────

    @Test
    void graduated_additive_and_clamped() {
        PriorFold.Result r = PriorFold.fold(0.40, List.of(
                PriorDelta.graduated(+0.20, "promote", "0.65"),
                PriorDelta.graduated(-0.10, "weak confirm", "0.95")
        ));
        assertEquals(0.50, r.livePrior(), 1e-9);
        assertFalse(r.eliminated());
    }

    @Test
    void graduated_clamps_at_1_0_high() {
        PriorFold.Result r = PriorFold.fold(0.60, List.of(
                PriorDelta.graduated(+0.30, "x", "0"),
                PriorDelta.graduated(+0.40, "y", "0")
        ));
        assertEquals(1.0, r.livePrior(), 1e-9);
    }

    @Test
    void graduated_clamps_at_0_0_low() {
        PriorFold.Result r = PriorFold.fold(0.10, List.of(
                PriorDelta.graduated(-0.40, "x", "0")
        ));
        assertEquals(0.0, r.livePrior(), 1e-9);
        // Not eliminated — just hit the floor of [0,1]; only CATEGORICAL_ELIMINATE sets the flag.
        assertFalse(r.eliminated());
    }

    @Test
    void graduated_commutative_under_clamp_in_interior() {
        // Q4 — within [0,1] interior, GRADUATED deltas commute. Engine sorts by (round,pass,id) for
        // future-proofing but should currently be order-independent.
        List<PriorDelta> ab = List.of(
                PriorDelta.graduated(+0.20, "a", "0"),
                PriorDelta.graduated(-0.10, "b", "0")
        );
        List<PriorDelta> ba = List.of(
                PriorDelta.graduated(-0.10, "b", "0"),
                PriorDelta.graduated(+0.20, "a", "0")
        );
        assertEquals(PriorFold.fold(0.50, ab).livePrior(),
                     PriorFold.fold(0.50, ba).livePrior(),
                     1e-9);
    }

    // ───────────── FLOOR_SET ───────────────────────────────────────────────────

    @Test
    void floor_set_raises_below_floor() {
        PriorFold.Result r = PriorFold.fold(0.10, List.of(
                PriorDelta.floorSet(0.30, "alt", "0.5")
        ));
        assertEquals(0.30, r.livePrior(), 1e-9);
    }

    @Test
    void floor_set_does_not_lower_above_floor() {
        // Floor is a MAX operation — it never reduces prior.
        PriorFold.Result r = PriorFold.fold(0.50, List.of(
                PriorDelta.floorSet(0.30, "alt", "0.5")
        ));
        assertEquals(0.50, r.livePrior(), 1e-9);
    }

    @Test
    void floor_set_max_of_multiple() {
        PriorFold.Result r = PriorFold.fold(0.05, List.of(
                PriorDelta.floorSet(0.20, "a", "0"),
                PriorDelta.floorSet(0.35, "b", "0"),
                PriorDelta.floorSet(0.10, "c", "0")
        ));
        assertEquals(0.35, r.livePrior(), 1e-9);
    }

    // ───────────── CATEGORICAL_ELIMINATE ───────────────────────────────────────

    @Test
    void eliminate_sets_flag_and_zeros_prior() {
        PriorFold.Result r = PriorFold.fold(0.60, List.of(
                PriorDelta.eliminate("Rule 0.96", "0.96")
        ));
        assertTrue(r.eliminated());
        assertEquals(0.0, r.livePrior(), 1e-9);
    }

    @Test
    void eliminate_is_sticky_later_deltas_do_not_revive() {
        // Q5 — once eliminated, later firings are audit-only; they MUST NOT modify the prior.
        PriorFold.Result r = PriorFold.fold(0.60, List.of(
                PriorDelta.eliminate("kill", "0.96"),
                PriorDelta.graduated(+0.50, "spurious promote", "0.65"),
                PriorDelta.floorSet(0.40, "spurious floor", "0.5")
        ));
        assertTrue(r.eliminated());
        assertEquals(0.0, r.livePrior(), 1e-9);
    }

    @Test
    void eliminate_idempotent() {
        PriorFold.Result r = PriorFold.fold(0.30, List.of(
                PriorDelta.eliminate("first", "0.96"),
                PriorDelta.eliminate("second", "0.96")
        ));
        assertTrue(r.eliminated());
        assertEquals(0.0, r.livePrior(), 1e-9);
    }

    // ───────────── mixed scenarios ─────────────────────────────────────────────

    @Test
    void rule_0_5_misalignment_demotes_original_and_floors_alt() {
        // Spec example (companion 098c2fd0): Rule 0.5 Wk-D misalignment → graduated demote on
        // original macro + floor_set(0.30) on spawned alternative.
        PriorFold.Result original = PriorFold.fold(0.50, List.of(
                PriorDelta.graduated(-0.20, "Rule 0.5 demote", "0.5")
        ));
        PriorFold.Result alt = PriorFold.fold(0.10, List.of(
                PriorDelta.floorSet(0.30, "Rule 0.5 alternative", "0.5")
        ));
        assertEquals(0.30, original.livePrior(), 1e-9);
        assertEquals(0.30, alt.livePrior(), 1e-9);
        assertFalse(original.eliminated());
        assertFalse(alt.eliminated());
    }
}
