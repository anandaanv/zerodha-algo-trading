package com.dtech.aitrader.v2.rules;

/**
 * Coarse macro-regime classification from {@link ContextProbe}. Five buckets keep the
 * {@code context_signature} cardinality bounded.
 *
 * <p>Derivation (per spec {@code f23b95be}): combination of synthetic-weekly ADX, EMA50/200 stack,
 * and a 6-month price slope. STRONG when all three agree forcefully, WEAK when partial agreement,
 * SIDEWAYS when ADX is low and slope is flat.
 */
public enum MacroRegime {
    UPTREND_STRONG,
    UPTREND_WEAK,
    SIDEWAYS,
    DOWNTREND_WEAK,
    DOWNTREND_STRONG,
    UNKNOWN
}
