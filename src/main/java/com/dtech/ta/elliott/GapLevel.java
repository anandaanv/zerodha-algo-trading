package com.dtech.ta.elliott;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

/**
 * An unfilled (or partially filled) price gap.
 * Gap zones act as support/resistance — unfilled gaps are magnets for price.
 *
 * Gap-Up zone:  gapBottom = prior bar's high, gapTop = current bar's low
 * Gap-Down zone: gapBottom = current bar's high, gapTop = prior bar's low
 */
@Data
@Builder
public class GapLevel {

    public enum GapDirection { GAP_UP, GAP_DOWN }
    public enum GapFillStatus { UNFILLED, PARTIALLY_FILLED, FILLED }
    public enum GapSignificance { MAJOR, SIGNIFICANT, MINOR }

    private String timeframe;
    private double gapTop;        // upper edge of gap zone
    private double gapBottom;     // lower edge of gap zone
    private double gapMidpoint;   // (gapTop + gapBottom) / 2
    private GapDirection direction;
    private GapFillStatus fillStatus;
    private int barIndex;         // bar where the gap occurred
    private LocalDate date;
    private double gapSizePct;    // (gapTop - gapBottom) / gapBottom * 100
    private double volumeRatio;   // gap-bar volume / 20-bar avg — >2 = institutional
    private GapSignificance significance;
    private boolean nearCurrentPrice; // midpoint within 5% of current price
}
