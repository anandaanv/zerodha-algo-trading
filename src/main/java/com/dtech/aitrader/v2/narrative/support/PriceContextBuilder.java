package com.dtech.aitrader.v2.narrative.support;

import com.dtech.aitrader.v2.narrative.beat.PriceContext;
import com.dtech.aitrader.v2.narrative.beat.PricePivotRef;
import com.dtech.aitrader.v2.narrative.beat.SwingState;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartdata.model.OhlcBarDTO;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility for building PriceContext from price pivots and bars.
 *
 * Provides classification of swing states (HH, HL, LH, LL) and context construction
 * at a given bar index.
 */
public class PriceContextBuilder {
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private PriceContextBuilder() {
        // Static utility class
    }

    /**
     * Classify swing states for each price pivot.
     *
     * For each ZigZagPoint, compares it to the previous pivot of the same type (HIGH or LOW).
     * - For HIGH pivots: compare to previous HIGH → HH (higher high) or LH (lower high)
     * - For LOW pivots: compare to previous LOW → HL (higher low) or LL (lower low)
     * - First HIGH and first LOW each get SwingState.NONE
     *
     * @param pricePivots list of ZigZagPoint pivots in chronological order
     * @return list of SwingState parallel to pricePivots
     */
    public static List<SwingState> classifySwingStates(List<ZigZagPoint> pricePivots) {
        List<SwingState> swingStates = new ArrayList<>();

        Double lastHighValue = null;
        Double lastLowValue = null;

        for (ZigZagPoint pivot : pricePivots) {
            SwingState state;

            if (pivot.isHigh()) {
                if (lastHighValue == null) {
                    state = SwingState.NONE;
                } else {
                    state = pivot.getValue() > lastHighValue ? SwingState.HH : SwingState.LH;
                }
                lastHighValue = pivot.getValue();
            } else { // LOW
                if (lastLowValue == null) {
                    state = SwingState.NONE;
                } else {
                    state = pivot.getValue() > lastLowValue ? SwingState.HL : SwingState.LL;
                }
                lastLowValue = pivot.getValue();
            }

            swingStates.add(state);
        }

        return swingStates;
    }

    /**
     * Build a PriceContext for a given bar index.
     *
     * Finds the nearest preceding price pivot, extracts its swing state, computes the
     * current bar's price value, and determines vsEvent heuristically.
     *
     * @param barIdx index into bars array
     * @param bars OHLC bar array
     * @param pricePivots list of ZigZagPoint pivots
     * @param swingStates parallel list of SwingState
     * @return PriceContext with swing state, price value, and event classification
     */
    public static PriceContext buildAt(int barIdx, List<OhlcBarDTO> bars, List<ZigZagPoint> pricePivots,
                                       List<SwingState> swingStates) {
        double priceValue = bars.get(barIdx).getClose();

        // Find nearest preceding price pivot
        PricePivotRef nearestPricePivot = null;
        SwingState swingState = SwingState.NONE;

        for (int i = pricePivots.size() - 1; i >= 0; i--) {
            ZigZagPoint pivot = pricePivots.get(i);
            if (pivot.getBarIndex() <= barIdx) {
                nearestPricePivot = PricePivotRef.builder()
                        .bar(pivot.getBarIndex())
                        .date(instantToDateString(pivot.getTimestamp()))
                        .price(pivot.getValue())
                        .kind(pivot.isHigh() ? "HIGH" : "LOW")
                        .build();
                swingState = swingStates.get(i);
                break;
            }
        }

        // Heuristic vsEvent classification
        String vsEvent = classifyVsEvent(barIdx, bars, priceValue);

        return PriceContext.builder()
                .swingState(swingState)
                .vsEvent(vsEvent)
                .priceValue(priceValue)
                .nearestPricePivot(nearestPricePivot)
                .build();
    }

    /**
     * Simple heuristic for vsEvent classification.
     *
     * Returns one of: "within_uptrend", "within_downtrend", "within_range"
     * (compared to the trailing 20-bar SMA).
     *
     * The LLM can recompute "is this bar the highest close in the dataset?" from
     * the price_value + when_bar pair on each beat, so a dataset-extreme flag here
     * would be redundant — and a sometimes-wrong one (the previous running-max
     * version fired on multiple bars per dataset) is worse than none.
     */
    private static String classifyVsEvent(int barIdx, List<OhlcBarDTO> bars, double priceValue) {
        // Compute 20-bar SMA
        int smaWindow = 20;
        double smaValue = 0.0;
        int smaCount = 0;
        for (int i = Math.max(0, barIdx - smaWindow + 1); i <= barIdx; i++) {
            smaValue += bars.get(i).getClose();
            smaCount++;
        }
        if (smaCount > 0) {
            smaValue /= smaCount;
        }

        if (priceValue > smaValue) {
            return "within_uptrend";
        } else if (priceValue < smaValue) {
            return "within_downtrend";
        } else {
            return "within_range";
        }
    }

    /**
     * Convert Instant to date string in "yyyy-MM-dd" format (IST).
     */
    private static String instantToDateString(Instant instant) {
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Kolkata"));
        return ldt.format(DATE_FORMATTER);
    }
}
