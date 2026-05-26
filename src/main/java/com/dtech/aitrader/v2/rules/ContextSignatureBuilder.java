package com.dtech.aitrader.v2.rules;

/**
 * Builds the controlled-vocabulary {@code context_signature} the eval layer groups by. Five-part
 * dot-separated string keeps cardinality bounded and parseable.
 *
 * <p>Format: {@code {RULE_ID}_{ROLE}_{SR}_{MACRO}_{CONFLUENCE}}. With our enums that is
 * roughly {@code 4 × 6 × 6 × 6 = 864} max combinations per rule — but in practice the rule's own
 * role-resolution + the natural correlation between macro and confluence collapse this to a few
 * dozen. The spec caps each rule at 60 distinct signatures; overflow is coerced to {@code _OTHER}
 * by the writer (deferred — pilot does not enforce yet, just observes).
 */
public final class ContextSignatureBuilder {

    private ContextSignatureBuilder() {}

    /**
     * Compose the canonical signature string. All inputs non-null — callers substitute the
     * {@code UNKNOWN} enum values when a probe dimension cannot be computed.
     */
    public static String build(String ruleId, Role role, ContextProbeResult ctx) {
        return ruleId
                + "_" + role.name()
                + "_" + ctx.getSrPosition().name()
                + "_" + ctx.getMacroRegime().name()
                + "_" + ctx.getIndicatorConfluence().name();
    }
}
