package com.dtech.kitecon.service.copilot.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * A labeled swing point from the market structure analysis.
 * Extends ZigZagPoint data with structural classification.
 */
@Data
@Builder
public class MarketStructurePoint {

    public enum PivotType { HIGH, LOW }

    /**
     * Structural label applied to this pivot:
     * HH — Higher High (uptrend continuation)
     * HL — Higher Low (uptrend continuation)
     * LH — Lower High (downtrend continuation)
     * LL — Lower Low (downtrend continuation)
     * BOS_HIGH — Break of Structure to the upside (price breaks above last swing high in downtrend)
     * BOS_LOW  — Break of Structure to the downside (price breaks below last swing low in uptrend)
     * CHOCH_HIGH — Change of Character: first Higher High in a downtrend (potential reversal signal)
     * CHOCH_LOW  — Change of Character: first Lower Low in an uptrend (potential reversal signal)
     */
    public enum StructureLabel {
        HH, HL, LH, LL,
        BOS_HIGH, BOS_LOW,
        CHOCH_HIGH, CHOCH_LOW,
        FIRST  // first pivot — no label yet
    }

    private PivotType pivotType;
    private StructureLabel structureLabel;
    private Instant timestamp;
    private double price;
    private double atrAtPivot;
    private Double retracementPct;
    private Double extensionPct;
    private Double rsiAtPivot;

    /** Whether this pivot represents a confirmed structure break */
    private boolean isStructureBreak;

    /** Brief description for AI prompt context */
    public String toDescription() {
        return String.format("%s at %.2f (%s) [%s]",
                pivotType, price, structureLabel,
                timestamp != null ? timestamp.toString() : "unknown");
    }
}
