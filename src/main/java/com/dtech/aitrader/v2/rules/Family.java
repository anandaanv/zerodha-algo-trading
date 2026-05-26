package com.dtech.aitrader.v2.rules;

/**
 * The rule family a {@link Rule} belongs to. Used by Pass 6 synthesis to detect cross-family
 * confluence ("≥2 families fired agreeing at the same level" = high conviction).
 *
 * <p>Per SPEC-004 ({@code 4e185036}): a Pass holds rules from MULTIPLE families. Family is a tag
 * on each firing; the engine itself does not branch on family.
 */
public enum Family {
    /** Elliott Wave validators + enumerators + leg examiners. */
    EW,
    /** Chart pattern detectors (Double-Top, H&S, triangle, etc.). */
    PATTERN,
    /** Indicator-derived rules (MACD cross, RSI divergence, ADX regime, etc.). */
    INDICATOR,
    /** Structural facts (pivot labels, cluster scan, macro anchor). */
    STRUCTURE,
    /** Pass-6 verdict-emitting rules — fold the chain, emit tradability VERDICT. */
    SYNTHESIS
}
