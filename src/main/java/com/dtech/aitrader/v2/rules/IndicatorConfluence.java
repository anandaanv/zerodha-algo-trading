package com.dtech.aitrader.v2.rules;

/**
 * Aggregate directional vote across {MACD, RSI, ADX-direction, EMA-stack}. The fourth signature
 * dimension. Bounded cardinality.
 *
 * <p>Derivation (per spec {@code f23b95be}): score each component {+1, 0, -1}. Sum:
 * <ul>
 *   <li>{@code BULL_HIGH}  — ≥3 bullish AND 0 bearish</li>
 *   <li>{@code BULL_MIXED} — 2 bullish AND ≤1 bearish</li>
 *   <li>{@code BEAR_HIGH}  — symmetric</li>
 *   <li>{@code BEAR_MIXED} — symmetric</li>
 *   <li>{@code NEUTRAL}    — anything else</li>
 * </ul>
 */
public enum IndicatorConfluence {
    BULL_HIGH,
    BULL_MIXED,
    NEUTRAL,
    BEAR_MIXED,
    BEAR_HIGH,
    UNKNOWN
}
