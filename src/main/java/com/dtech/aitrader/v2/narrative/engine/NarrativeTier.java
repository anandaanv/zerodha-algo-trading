package com.dtech.aitrader.v2.narrative.engine;

/**
 * Story-archetype tier of an indicator. From Narrative Core spec (memsys 533b3e85), Section 0.
 *
 * <ul>
 *   <li>{@link #FULL_NARRATIVE} — trajectory indicators (MACD, RSI, Stochastic, StochRSI). Use
 *       the full engine: episodes + events + structural-relations + divergence + thrust.</li>
 *   <li>{@link #REGIME_EPISODE} — state + transitions (ADX/DMI, Aroon, EMA-stack, Bollinger,
 *       Donchian). Use only episode + event archetypes; no divergence/thrust.</li>
 *   <li>{@link #SNAPSHOT} — current-state facts only (Ichimoku, VWAP/AVWAP). No narrative,
 *       emitted as state lines.</li>
 * </ul>
 */
public enum NarrativeTier {
    FULL_NARRATIVE,
    REGIME_EPISODE,
    SNAPSHOT
}
