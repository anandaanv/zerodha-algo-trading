package com.dtech.aitrader.v2.rules.ew.signature;

/**
 * Vocabulary for the leg-character-signature framework (SPEC owner reframe {@code 159ba913}).
 *
 * <p>Every EW form is expressed as a SEQUENCE of leg characters. Examples:
 * <ul>
 *   <li>Zigzag    = {@code FIVE-THREE-FIVE}            (A=5, B=3, C=5)</li>
 *   <li>Flat      = {@code THREE-THREE-FIVE}           (A=3, B=3, C=5)</li>
 *   <li>Triangle  = {@code THREE-THREE-THREE-THREE-THREE}</li>
 *   <li>Impulse   = {@code FIVE-THREE-FIVE-THREE-FIVE} (W1=5, W2=3, W3=5, W4=3, W5=5)</li>
 * </ul>
 *
 * <p>A signature rule's admission check compares OBSERVED leg characters against its declared
 * signature. An {@link #INDETERMINATE} observed character matches any expected character (partial
 * data). A mismatch on a determinate character invalidates the hypothesis.
 *
 * <p>Decision matrix per the reframe:
 * <pre>
 *   expected   observed       result
 *   ─────────  ─────────────  ────────────────────────────
 *   THREE      THREE          match
 *   THREE      FIVE           CONTRADICTION → invalidate
 *   FIVE       FIVE           match
 *   FIVE       THREE          CONTRADICTION → invalidate
 *   ANY        INDETERMINATE  pending (not enough data yet)
 * </pre>
 *
 * <p><b>Vocabulary scope (PHASE 1):</b> three values. The PHASE-A bridge discriminator
 * (sub-pivot-count + retest + retrace) computes character; PHASE-B pattern-classifier may enrich
 * (sub-divisions, gap-existence, etc.) but the enum itself stays small. Owner asked for an
 * engineering opinion on whether richer per-leg attributes are needed — captured in the
 * impl-response.
 */
public enum LegCharacter {
    /** Observed as 3-wave / corrective / overlapping sub-structure. */
    THREE,
    /** Observed as 5-wave / impulsive / one-directional sub-structure. */
    FIVE,
    /** Insufficient sub-pivots to classify; matches any expected character. */
    INDETERMINATE
}
