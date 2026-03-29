package com.dtech.ta.trendline;

import com.dtech.ta.elliott.EnrichedPivot;
import org.ta4j.core.BarSeries;
import java.util.List;

/**
 * A pluggable trendline detection scenario.
 * Each scenario independently produces scored trendline candidates.
 */
public interface TrendlineScenario {

    String name();

    List<VirginTrendline> detect(List<EnrichedPivot> pivots, BarSeries series);

    default void score(List<VirginTrendline> allCandidates, List<EnrichedPivot> pivots, BarSeries series) {}

    default double slope(EnrichedPivot p1, EnrichedPivot p2) {
        return (p2.getPrice() - p1.getPrice()) / (double)(p2.getBarIndex() - p1.getBarIndex());
    }

    default double intercept(EnrichedPivot p1, double slope) {
        return p1.getPrice() - slope * p1.getBarIndex();
    }

    /**
     * Virginity check:
     * - Between A and B (exclusive): ZERO tolerance — any low below line (support)
     *   or high above line (resistance) immediately invalidates.
     * - After B: 0.5×ATR tolerance — minor wicks allowed, only clean breaks count.
     */
    default boolean isVirgin(EnrichedPivot p1, EnrichedPivot p2,
                              double slope, double intercept,
                              List<EnrichedPivot> allPivots, boolean support) {
        for (EnrichedPivot pivot : allPivots) {
            int bi = pivot.getBarIndex();
            if (bi <= p1.getBarIndex()) continue;  // before or at A: skip
            if (bi == p2.getBarIndex()) continue;   // B anchor itself: skip

            double trendlinePrice = slope * bi + intercept;
            boolean betweenAnchors = bi < p2.getBarIndex();

            if (betweenAnchors) {
                // Zero tolerance between A and B — any breach is fatal
                if (support && pivot.isLow() && pivot.getPrice() < trendlinePrice) return false;
                if (!support && pivot.isHigh() && pivot.getPrice() > trendlinePrice) return false;
            } else {
                // After B — allow up to 0.5×ATR wick before calling it broken
                double tolerance = pivot.getAtrAtPivot() * 0.5;
                if (support && pivot.isLow() && pivot.getPrice() < trendlinePrice - tolerance) return false;
                if (!support && pivot.isHigh() && pivot.getPrice() > trendlinePrice + tolerance) return false;
            }
        }
        return true;
    }

    default VirginTrendline build(EnrichedPivot p1, EnrichedPivot p2,
                                   boolean support, int score, String tag,
                                   int currentBarIndex, double currentPrice) {
        double s = slope(p1, p2);
        double ic = intercept(p1, s);
        double priceAtCurrent = s * currentBarIndex + ic;
        double distancePct = Math.abs(currentPrice - priceAtCurrent) / currentPrice;
        String label = (p1.getStructureLabel() != null ? p1.getStructureLabel().name() : "P")
                + "→"
                + (p2.getStructureLabel() != null ? p2.getStructureLabel().name() : "P");
        return VirginTrendline.builder()
                .label(label)
                .anchor1(p1).anchor2(p2)
                .slope(s).intercept(ic)
                .support(support)
                .priceAtCurrentBar(priceAtCurrent)
                .distancePctFromCurrentPrice(distancePct)
                .score(score)
                .scenarioTags(new java.util.ArrayList<>(java.util.List.of(tag)))
                .build();
    }
}
