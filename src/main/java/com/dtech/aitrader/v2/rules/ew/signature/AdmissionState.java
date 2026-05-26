package com.dtech.aitrader.v2.rules.ew.signature;

/**
 * Possible outcomes when a signature rule evaluates observed legs against its declared signature.
 * Per the SPEC owner reframe ({@code 159ba913}): validity is no-invalidation-yet — hypotheses
 * stay alive until contradicted by a determinate leg-character that violates the signature.
 */
public enum AdmissionState {
    /**
     * Observed leg characters match (or partially-match) the signature's leading positions. The
     * hypothesis is LIVE — contributes its watch + invalidation levels to the engine's level-map.
     * Partial matches (some legs still INDETERMINATE) qualify as ADMITTED — the rule says
     * "admissible because prior structure qualifies; pending confirmation."
     */
    ADMITTED,

    /**
     * A determinate observed leg-character contradicts the signature at its position. The
     * hypothesis is dead — removed from the live set. Per owner's bigger-impulse worked example:
     * an observed {@code FIVE} where the signature requires {@code THREE} (or vice versa)
     * invalidates the hypothesis.
     */
    INVALIDATED,

    /**
     * Not enough observed legs yet to admit or invalidate. The signature rule has not seen
     * enough data — the hypothesis stays in "possibility" status (admissible because prior
     * structure leaves it open) without being LIVE yet.
     */
    PENDING
}
