package com.dtech.aitrader.v2.rules;

/**
 * For CANDIDATE firings spawned from a Pass-5 CONTRADICTION (feedback / macro-revisit), declares
 * whether the spawned candidate re-uses the existing Pass-1 structural facts or needs a scoped
 * Pass-1 recompute around a new anchor.
 *
 * <p>This is the owner's Q2 override (convergence memo {@code 9c60e777}): re-anchoring is real,
 * and the architecture is "forward-with-rounds where rounds can re-enter at Pass 1 when the
 * anchor moves."
 */
public enum SpawnAnchorMode {
    /** Same pivot universe + anchor as round 1 — re-enters at Pass 3 (validate). */
    SAME_ANCHOR(Pass.P3_VALIDATION),
    /** Different / earlier anchor → needs scoped structural recompute first. Re-enters at Pass 1. */
    RE_ANCHOR(Pass.P1_STRUCTURAL);

    /** Pass at which the spawned candidate's next-round evaluation begins. */
    public final Pass entryPass;

    SpawnAnchorMode(Pass entryPass) {
        this.entryPass = entryPass;
    }
}
