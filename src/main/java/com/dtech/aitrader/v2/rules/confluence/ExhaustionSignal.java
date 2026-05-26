package com.dtech.aitrader.v2.rules.confluence;

import java.util.Map;

/**
 * Evidence of momentum exhaustion near a wave target level. Produced by {@link ExhaustionDetector}
 * and consumed by {@link EwExhaustionAtTargetRule} (owner memo {@code 34418c54}, Q2 position:
 * exhaustion = (a) RSI divergence between two price swings approaching the target, or
 * (b) MACD-histogram contraction over the last 5 bars).
 *
 * <p>{@code direction} = BEARISH means the signal tilts a LONG continuation thesis toward
 * REVERSAL (down); {@code BULLISH} tilts a SHORT continuation thesis toward REVERSAL (up). It is
 * the direction the EVIDENCE points, NOT the hypothesis the rule consumes.
 */
public record ExhaustionSignal(
        String kind,            // "RSI_DIVERGENCE" | "MACD_HISTOGRAM_CONTRACTION"
        String tf,              // TF on which the signal was observed
        String direction,       // "BEARISH" | "BULLISH"
        Map<String, Object> evidence) {
}
