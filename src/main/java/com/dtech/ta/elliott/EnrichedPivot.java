package com.dtech.ta.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * A ZigZag pivot enriched with all indicator values at that bar index.
 * This is the fundamental unit for all Elliott Wave and pattern analysis.
 */
@Data
@Builder
public class EnrichedPivot {

    // ── Pivot identity ────────────────────────────────────────────────────────
    private ZigZagPoint.Type type;
    private Instant timestamp;
    private int barIndex;
    private double price;
    private double atrAtPivot;
    private Double retracementPct;   // retracement from prior pivot (opposite type)
    private Double extensionPct;     // extension beyond the swing that preceded the prior pivot

    // ── Dow Theory label ─────────────────────────────────────────────────────
    private MarketStructurePoint.StructureLabel structureLabel;

    // ── MACD (12, 26, 9) ─────────────────────────────────────────────────────
    private double macd;
    private double macdSignal;
    private double macdHistogram;

    /**
     * Absolute MACD histogram amplitude = Math.abs(macdHistogram).
     * Used to measure W4 contraction — amplitude should shrink during W4.
     */
    private double macdHistogramAmplitude;

    /**
     * MACD amplitude ratio = current amplitude / peak amplitude in last 20 pivots.
     * < 0.2 = near zero (W4 lower-TF oscillation signature).
     * < 0.4 = contracting (W4 higher-TF amplitude contraction signature).
     */
    private double macdAmplitudeRatio;

    /**
     * True if MACD histogram amplitude is < 20% of the peak amplitude
     * seen in the last 20 pivots — the "hovering around zero" W4 sub-wave signature.
     */
    private boolean macdNearZero;

    // ── EWO — Elliott Wave Oscillator = MACD(5, 35) ──────────────────────────
    private double ewo;

    // ── RSI (14) ─────────────────────────────────────────────────────────────
    private double rsi;

    // ── Stochastic (14, 3) ───────────────────────────────────────────────────
    private double stochK;
    private double stochD;

    // ── Bollinger Bands (20, 2) ───────────────────────────────────────────────
    private double bollingerUpper;
    private double bollingerMiddle;  // 20-period SMA
    private double bollingerLower;
    private double bollingerPctB;    // (price - lower) / (upper - lower)
    private double bollingerWidth;   // (upper - lower) / middle  — bandwidth normalised

    // ── ADX / DMI ────────────────────────────────────────────────────────────
    private double adx;
    private double diPlus;
    private double diMinus;

    // ── Volume indicators ────────────────────────────────────────────────────
    private double obv;              // On-Balance Volume
    private double mfi;              // Money Flow Index (14)
    private double volume;
    private double relativeVolume;   // volume / 20-period avg volume

    // ── EMA stack ────────────────────────────────────────────────────────────
    private double ema20;
    private double ema50;
    private double ema200;

    // ── Derived flags (computed from above values) ────────────────────────────
    /** Price is above the upper Bollinger Band — classic Wave 3 / strong momentum indicator */
    private boolean bollingerWalk;

    /** Band width is at a multi-period low — squeeze, breakout imminent */
    private boolean bollingerSqueeze;

    /**
     * EMA stack alignment:
     * BULLISH  — price > ema20 > ema50 > ema200
     * BEARISH  — price < ema20 < ema50 < ema200
     * MIXED    — otherwise
     */
    private String emaStackOrder;

    // ── Stoch-RSI ─────────────────────────────────────────────────────────────
    private double stochRsiK;
    private double stochRsiD;

    // ── Convenience ──────────────────────────────────────────────────────────
    public boolean isHigh() { return type == ZigZagPoint.Type.HIGH; }
    public boolean isLow()  { return type == ZigZagPoint.Type.LOW; }

    public boolean isHH() { return structureLabel == MarketStructurePoint.StructureLabel.HH; }
    public boolean isHL() { return structureLabel == MarketStructurePoint.StructureLabel.HL; }
    public boolean isLH() { return structureLabel == MarketStructurePoint.StructureLabel.LH; }
    public boolean isLL() { return structureLabel == MarketStructurePoint.StructureLabel.LL; }
    public boolean isBosHigh()   { return structureLabel == MarketStructurePoint.StructureLabel.BOS_HIGH; }
    public boolean isBosLow()    { return structureLabel == MarketStructurePoint.StructureLabel.BOS_LOW; }
    public boolean isChochHigh() { return structureLabel == MarketStructurePoint.StructureLabel.CHOCH_HIGH; }
    public boolean isChochLow()  { return structureLabel == MarketStructurePoint.StructureLabel.CHOCH_LOW; }

    public boolean isBullishStructure() {
        return isHH() || isHL() || isBosHigh() || isChochHigh();
    }

    public boolean isBearishStructure() {
        return isLH() || isLL() || isBosLow() || isChochLow();
    }
}
