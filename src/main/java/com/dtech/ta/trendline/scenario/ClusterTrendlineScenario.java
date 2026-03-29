package com.dtech.ta.trendline.scenario;

import com.dtech.ta.elliott.EnrichedPivot;
import com.dtech.ta.trendline.TrendlineScenario;
import com.dtech.ta.trendline.VirginTrendline;
import org.ta4j.core.BarSeries;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Detects trendlines where two same-direction pivots (LH-LH or HL-HL)
 * have prices within 1 ATR of each other — a price cluster.
 * These nearly-horizontal trendlines often act as strong S/R zones.
 * Weight: 20.
 */
public class ClusterTrendlineScenario implements TrendlineScenario {

    @Override
    public String name() { return "Cluster"; }

    @Override
    public List<VirginTrendline> detect(List<EnrichedPivot> pivots, BarSeries series) {
        List<VirginTrendline> result = new ArrayList<>();
        if (pivots == null || pivots.size() < 2 || series == null) return result;

        int currentBarIndex = series.getBarCount() - 1;
        double currentPrice = series.getBar(currentBarIndex).getClosePrice().doubleValue();

        // HL clusters (support)
        List<EnrichedPivot> hls = pivots.stream()
                .filter(p -> p.getStructureLabel() != null && p.isLow())
                .collect(Collectors.toList());

        // LH clusters (resistance)
        List<EnrichedPivot> lhs = pivots.stream()
                .filter(p -> p.getStructureLabel() != null && p.isHigh())
                .collect(Collectors.toList());

        result.addAll(buildClusterLines(hls, pivots, true, currentBarIndex, currentPrice));
        result.addAll(buildClusterLines(lhs, pivots, false, currentBarIndex, currentPrice));
        return result;
    }

    private List<VirginTrendline> buildClusterLines(List<EnrichedPivot> anchors,
                                                     List<EnrichedPivot> allPivots,
                                                     boolean support,
                                                     int currentBarIndex, double currentPrice) {
        List<VirginTrendline> result = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            for (int j = i + 1; j < anchors.size(); j++) {
                EnrichedPivot p1 = anchors.get(i);
                EnrichedPivot p2 = anchors.get(j);
                if (p1.getBarIndex() >= p2.getBarIndex()) continue;

                // Cluster condition: price difference within 1 ATR
                double atr = Math.max(p1.getAtrAtPivot(), p2.getAtrAtPivot());
                if (Math.abs(p1.getPrice() - p2.getPrice()) > atr) continue;

                double s = slope(p1, p2);
                double ic = intercept(p1, s);
                if (isVirgin(p1, p2, s, ic, allPivots, support)) {
                    result.add(build(p1, p2, support, 20, name(), currentBarIndex, currentPrice));
                }
            }
        }
        return result;
    }
}
