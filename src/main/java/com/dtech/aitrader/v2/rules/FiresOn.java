package com.dtech.aitrader.v2.rules;

/**
 * What kind of firing a rule emits. Drives downstream behaviour — most importantly that only
 * {@link #VERDICT} firings get an outcome row (Q7 in the convergence memo {@code 9c60e777}).
 *
 * <p>A firing's {@code fires_on} answers: "is this asserting a fact, claiming a hypothesis,
 * killing a hypothesis, scoring a hypothesis, confirming a sub-structure, or pronouncing a
 * tradable verdict?" The eval/outcome layer only cares about the last one; the rest are
 * explanation/audit.
 */
public enum FiresOn {
    /** A structural FACT (e.g. "MACD bull-cross occurred at bar 412"). Emitted in Pass 1. */
    FACT(false),
    /** A hypothesis (e.g. "this could be impulse-W3"). Emitted in Pass 2 (or spawned later). */
    CANDIDATE(false),
    /** Hard-rule killing of a candidate (e.g. "W4 overlaps W1 → impulse impossible"). */
    ELIMINATION(false),
    /** Magnitude / quality scoring on a surviving candidate; carries a prior_delta. */
    CLASSIFICATION(false),
    /** Sub-structure / divergence (con|dis)firmation on a candidate; carries a prior_delta. */
    CONFIRMATION(false),
    /** Pass-6 tradability verdict — the only outcome-bearing firing kind. */
    VERDICT(true),
    /**
     * Pass-6 "watching" firing — carries the engine's best structural read when the winning
     * candidate's terminal wave is NOT yet COMPLETE. Surfaces the in-progress framing for
     * observability (UI, audit) without polluting the eval contract: WATCH firings are NOT
     * outcome-bearing (per SPEC-006 escalation design — the engine commits to the highest thesis
     * but doesn't pretend it's tradable yet). Walk-forward scorer ignores these.
     */
    WATCH(false);

    private final boolean outcomeBearing;

    FiresOn(boolean outcomeBearing) {
        this.outcomeBearing = outcomeBearing;
    }

    /**
     * True iff this firing kind should receive a {@code firing_outcome} row from the walk-forward
     * scorer. Per Q7 — only VERDICT firings represent actionable predictions; everything else is
     * explanation and produces no outcome.
     */
    public boolean isOutcomeBearing() {
        return outcomeBearing;
    }
}
