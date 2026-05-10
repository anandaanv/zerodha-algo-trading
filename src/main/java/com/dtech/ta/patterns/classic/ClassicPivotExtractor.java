package com.dtech.ta.patterns.classic;

import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Williams-style pivot extractor: identifies local extrema over a configurable window.
 * Reference: docs/spec-classic-patterns-port.md (get_max_min logic)
 */
public class ClassicPivotExtractor implements PivotExtractor {

    private static final int DEFAULT_BARS_LEFT = 6;
    private static final int DEFAULT_BARS_RIGHT = 6;

    /**
     * Extract pivots with default windows (barsLeft=6, barsRight=6).
     */
    public List<PivotPoint> extract(BarSeries series, PivotType type) {
        return extract(series, DEFAULT_BARS_LEFT, DEFAULT_BARS_RIGHT, type);
    }

    /**
     * Extract pivots with specified windows.
     * For each bar i where barsLeft <= i <= endIndex - barsRight, check if it's
     * a local high or low over [i-barsLeft, i+barsRight] inclusive.
     */
    @Override
    public List<PivotPoint> extract(BarSeries series, int barsLeft, int barsRight, PivotType type) {
        List<PivotPoint> highs = new ArrayList<>();
        List<PivotPoint> lows = new ArrayList<>();

        int endIndex = series.getEndIndex();

        for (int i = barsLeft; i <= endIndex - barsRight; i++) {
            Bar centerBar = series.getBar(i);
            double centerHigh = centerBar.getHighPrice().doubleValue();
            double centerLow = centerBar.getLowPrice().doubleValue();

            boolean isLocalHigh = true;
            boolean isLocalLow = true;

            for (int j = i - barsLeft; j <= i + barsRight; j++) {
                if (j != i) {
                    Bar otherBar = series.getBar(j);
                    if (otherBar.getHighPrice().doubleValue() > centerHigh) {
                        isLocalHigh = false;
                    }
                    if (otherBar.getLowPrice().doubleValue() < centerLow) {
                        isLocalLow = false;
                    }
                }
            }

            if (isLocalHigh) {
                highs.add(createPivot(series, i, centerHigh, PivotType.HIGH));
            }
            if (isLocalLow) {
                lows.add(createPivot(series, i, centerLow, PivotType.LOW));
            }
        }

        return switch (type) {
            case HIGH -> highs;
            case LOW -> lows;
            case BOTH -> mergeSorted(highs, lows);
        };
    }

    private PivotPoint createPivot(BarSeries series, int barIndex, double price, PivotType type) {
        Instant time = series.getBar(barIndex).getEndTime();
        return new PivotPoint(barIndex, time, price, type);
    }

    private List<PivotPoint> mergeSorted(List<PivotPoint> highs, List<PivotPoint> lows) {
        List<PivotPoint> result = new ArrayList<>(highs.size() + lows.size());
        result.addAll(highs);
        result.addAll(lows);
        result.sort((a, b) -> Integer.compare(a.barIndex(), b.barIndex()));
        return result;
    }
}
