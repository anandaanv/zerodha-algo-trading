package com.dtech.aitrader.v2.rules.confluence;

/**
 * Tests whether the current price sits within a deterministic band around a wave-target level. The
 * band is defined in ATR multiples — chosen because ATR is already the canonical structure-degree
 * scale used by the pattern detectors ({@code DoubleTopDetectRule.EQUAL_HIGH_TOLERANCE_ATR}) and
 * the macro-anchor rule. Default multiplier is 1.5×ATR per owner memo {@code 34418c54}
 * brainstorm-Q1 position (proximity is geometric, not statistical).
 */
public final class TargetProximityChecker {

    public static final double DEFAULT_ATR_MULTIPLIER = 1.5;

    private TargetProximityChecker() { }

    public static boolean withinBand(double currentPrice, double targetPrice, double atr,
                                       double atrMultiplier) {
        if (atr <= 0.0) return false;                       // zero/neg ATR ⇒ no usable band
        double band = atr * atrMultiplier;
        return Math.abs(currentPrice - targetPrice) <= band;
    }

    public static double distance(double currentPrice, double targetPrice) {
        return Math.abs(currentPrice - targetPrice);
    }
}
