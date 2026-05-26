package com.dtech.aitrader.v2.rules;

/**
 * Per-firing role tag — how the rule's geometry should be interpreted given the macro/SR context.
 * Used as one dimension of the {@code context_signature}, which the eval layer groups by.
 *
 * <p>The role is computed by each rule from its {@link ContextProbeResult} reading + its own
 * directional bias. Same geometry at AT_MAJOR_SUPPORT in a DOWNTREND is a {@link #REVERSAL};
 * the same geometry mid-uptrend is a {@link #CONTINUATION}.
 */
public enum Role {
    /** Geometry resolves the prior trend (e.g. DB at major support in a downtrend). */
    REVERSAL,
    /** Geometry resumes the prior trend (e.g. DB at higher-low in an uptrend). */
    CONTINUATION,
    /** Geometry sits at an extended position prone to mean-reversion failure. */
    FAKEOUT_RISK,
    /** Geometry doesn't fit any clear role given current context — store for eval, don't trade. */
    NEUTRAL
}
