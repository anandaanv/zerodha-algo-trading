package com.dtech.aitrader.model;

/**
 * Represents an exit decision from ExitAgent for an open paper trade.
 * Records the action (HOLD, CLOSE_NOW, MOVE_TO_BREAKEVEN, etc.), optional new
 * stop/target, confidence, reasoning, and LLM usage metrics.
 */
public record ExitDecision(
    String action,                  // HOLD | CLOSE_NOW | MOVE_TO_BREAKEVEN | TRAIL_TO_X | EXTEND_TARGET_TO_Y
    Double newStop,                 // nullable
    Double newTarget,               // nullable
    Double confidence,
    String reasoning,
    int inputTokens,
    int outputTokens,
    double costUsd,
    String modelUsed
) {
}
