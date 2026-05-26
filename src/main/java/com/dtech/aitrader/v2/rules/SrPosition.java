package com.dtech.aitrader.v2.rules;

/**
 * Where the current close sits relative to historical pivot clusters (the closest thing to
 * "support / resistance" we can compute deterministically). One enum out of five — bounded
 * cardinality, signature-friendly.
 *
 * <p>Derivation (per spec {@code f23b95be}): cluster last-250-bar pivots in ATR-bands, find
 * "major" clusters (≥3 pivots), compare current close to nearest cluster centre.
 */
public enum SrPosition {
    /** Within 1 ATR of a major historical-low cluster — strong floor potential. */
    AT_MAJOR_SUPPORT,
    /** Within 1 ATR of a major historical-high cluster — strong ceiling potential. */
    AT_MAJOR_RESISTANCE,
    /** Between clusters, no immediate level. */
    MID_RANGE,
    /** Above all historical clusters by ≥2 ATR — extended; mean-reversion risk on longs. */
    EXTENDED_HIGH,
    /** Below all historical clusters by ≥2 ATR — extended; mean-reversion risk on shorts. */
    EXTENDED_LOW,
    UNKNOWN
}
