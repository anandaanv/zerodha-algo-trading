package com.dtech.ta.trendline;

import com.dtech.ta.elliott.EnrichedPivot;
import com.dtech.ta.elliott.WaveCount;
import com.dtech.ta.elliott.WaveLabel;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class VirginTrendlineDetector {

    /**
     * Detects virgin (never-retested) trendlines connecting key wave structural pivots.
     * Only processes IMPULSE wave types.
     *
     * @param waveCount the wave count to analyze
     * @param series the bar series for price/bar index calculations
     * @return list of virgin trendlines, sorted by proximity to current price
     */
    public List<VirginTrendline> detect(WaveCount waveCount, BarSeries series) {
        List<VirginTrendline> result = new ArrayList<>();

        // Only process IMPULSE wave types
        if (waveCount.getWaveType() != WaveCount.WaveType.IMPULSE) {
            return result;
        }

        List<EnrichedPivot> pivots = waveCount.getPivots();
        Map<Integer, WaveLabel> pivotToWave = waveCount.getPivotToWave();
        boolean bullish = waveCount.isBullish();

        if (pivots == null || pivots.isEmpty() || pivotToWave == null) {
            return result;
        }

        // Build candidate pivot list: origin and key wave pivots
        List<EnrichedPivot> candidates = new ArrayList<>();
        candidates.add(pivots.get(0)); // Origin (first pivot, unlabeled)

        // Find W2, W4 (or W1, W3, W5 for bearish)
        EnrichedPivot w2Pivot = findPivotByLabel(pivots, pivotToWave, WaveLabel.W2);
        EnrichedPivot w4Pivot = findPivotByLabel(pivots, pivotToWave, WaveLabel.W4);

        if (w2Pivot != null) candidates.add(w2Pivot);
        if (w4Pivot != null) candidates.add(w4Pivot);

        // Generate all pairs (p1, p2) where p1.barIndex < p2.barIndex
        List<VirginTrendline> candidates_trendlines = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            for (int j = i + 1; j < candidates.size(); j++) {
                EnrichedPivot p1 = candidates.get(i);
                EnrichedPivot p2 = candidates.get(j);

                if (p1.getBarIndex() >= p2.getBarIndex()) continue;

                // Compute slope and intercept
                double barDiff = p2.getBarIndex() - p1.getBarIndex();
                double priceDiff = p2.getPrice() - p1.getPrice();
                double slope = priceDiff / barDiff;
                double intercept = p1.getPrice() - slope * p1.getBarIndex();

                // Derive labels
                String label1 = deriveLabelFromPivot(p1, pivotToWave);
                String label2 = deriveLabelFromPivot(p2, pivotToWave);
                String label = label1 + "→" + label2;

                // Virginity check: scan pivots AFTER p2
                boolean isVirgin = checkVirginityForBullish(pivots, p2.getBarIndex(), slope, intercept, bullish);

                if (!isVirgin) continue;

                // Compute price at current bar
                int currentBarIndex = series.getBarCount() - 1;
                double priceAtCurrentBar = slope * currentBarIndex + intercept;
                double currentPrice = series.getBar(currentBarIndex).getClosePrice().doubleValue();
                double distancePctFromCurrentPrice = Math.abs(currentPrice - priceAtCurrentBar) / currentPrice;

                VirginTrendline trendline = VirginTrendline.builder()
                        .label(label)
                        .anchor1(p1)
                        .anchor2(p2)
                        .slope(slope)
                        .intercept(intercept)
                        .support(bullish)  // true for bullish (support), false for bearish (resistance)
                        .priceAtCurrentBar(priceAtCurrentBar)
                        .distancePctFromCurrentPrice(distancePctFromCurrentPrice)
                        .build();

                candidates_trendlines.add(trendline);
            }
        }

        // Sort by distance from current price (closest first)
        candidates_trendlines.sort(Comparator.comparingDouble(VirginTrendline::getDistancePctFromCurrentPrice));
        return candidates_trendlines;
    }

    /**
     * Finds a pivot by its wave label.
     */
    private EnrichedPivot findPivotByLabel(List<EnrichedPivot> pivots,
                                           Map<Integer, WaveLabel> pivotToWave,
                                           WaveLabel targetLabel) {
        for (EnrichedPivot p : pivots) {
            WaveLabel label = pivotToWave.get(p.getBarIndex());
            if (label == targetLabel) {
                return p;
            }
        }
        return null;
    }

    /**
     * Derives the label string for a pivot (either the wave label or "Origin").
     */
    private String deriveLabelFromPivot(EnrichedPivot pivot, Map<Integer, WaveLabel> pivotToWave) {
        WaveLabel label = pivotToWave.get(pivot.getBarIndex());
        if (label != null) {
            return label.name();
        }
        return "Origin";
    }

    /**
     * Virginity check: scan pivots AFTER p2 to ensure the trendline is never retested.
     * For bullish (support): check if any LOW pivot after p2 dips below the line (minus ATR tolerance).
     * For bearish (resistance): check if any HIGH pivot after p2 rises above the line (plus ATR tolerance).
     */
    private boolean checkVirginityForBullish(List<EnrichedPivot> pivots, int p2BarIndex,
                                              double slope, double intercept, boolean bullish) {
        for (EnrichedPivot pivot : pivots) {
            if (pivot.getBarIndex() <= p2BarIndex) continue;

            double trendlinePrice = slope * pivot.getBarIndex() + intercept;
            double tolerance = pivot.getAtrAtPivot() * 0.5;

            if (bullish) {
                // For bullish support: check LOW pivots
                if (pivot.isLow() && pivot.getPrice() < trendlinePrice - tolerance) {
                    return false; // Trendline is broken
                }
            } else {
                // For bearish resistance: check HIGH pivots
                if (pivot.isHigh() && pivot.getPrice() > trendlinePrice + tolerance) {
                    return false; // Trendline is broken
                }
            }
        }
        return true; // Trendline is virgin
    }
}
