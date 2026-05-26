package com.dtech.aitrader.v2.rules;

/**
 * The seven-stage epistemic ordering of the multi-pass rule engine.
 *
 * <p>Per SPEC-004 ({@code 4e185036}) — owner's decision was ONE global pass-ordering where each
 * pass holds rules from multiple families (EW, PATTERN, INDICATOR, STRUCTURE, SYNTHESIS), NOT
 * separate per-family pipelines. Rationale: the tradable edge is cross-family CONFLUENCE in the
 * SAME context — siloing families until synthesis destroys the confluence signal.
 *
 * <p>The {@link #order} field is the authoritative pass number. {@link Enum#ordinal()} happens to
 * match, but tests assert this — relying on ordinal alone would silently break if someone reorders
 * the constants.
 */
public enum Pass {
    /** Compute the shared {@code ContextProbeResult} every firing is tagged with. */
    P0_CONTEXT_BUILD(0),
    /** Emit structural FACTS: pivot labels, cluster-scan, macro anchor, pattern primitives. */
    P1_STRUCTURAL(1),
    /** Enumerate CANDIDATES (EW wave-labellings, pattern instances) referencing Pass-1 facts. */
    P2_ENUMERATION(2),
    /** Validate / eliminate candidates against hard rules (Rule 3, Rule 0.96 categorical). */
    P3_VALIDATION(3),
    /** Classify magnitudes / quality on surviving candidates; emit prior_delta firings. */
    P4_CLASSIFICATION(4),
    /** Micro/nano sub-structure confirmation (Rule 0.95, indicator divergence). */
    P5_CONFIRMATION(5),
    /** Fold each candidate's chain → final prior; emit cross-family confluence VERDICTs. */
    P6_SYNTHESIS(6);

    /** Authoritative pass number used by the engine for ordering and the fold. */
    public final int order;

    Pass(int order) {
        this.order = order;
    }
}
