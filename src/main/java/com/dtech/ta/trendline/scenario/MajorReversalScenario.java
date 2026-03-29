package com.dtech.ta.trendline.scenario;

import com.dtech.ta.elliott.EnrichedPivot;
import com.dtech.ta.trendline.TrendlineScenario;
import com.dtech.ta.trendline.VirginTrendline;
import org.ta4j.core.BarSeries;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Connects BOS and CHOCH pivots — the major trend reversal points.
 * BOS_LOW / CHOCH_LOW lows → support lines.
 * BOS_HIGH / CHOCH_HIGH highs → resistance lines.
 * Weight: 40 (highest priority scenario).
 */
public class MajorReversalScenario implements TrendlineScenario {

    @Override
    public String name() { return "MajorReversal"; }

    @Override
    public List<VirginTrendline> detect(List<EnrichedPivot> pivots, BarSeries series) {
        List<VirginTrendline> result = new ArrayList<>();
        if (pivots == null || pivots.size() < 2 || series == null) return result;

        int currentBarIndex = series.getBarCount() - 1;
        double currentPrice = series.getBar(currentBarIndex).getClosePrice().doubleValue();

        List<EnrichedPivot> bosLows = pivots.stream()
                .filter(p -> p.getStructureLabel() != null)
                .filter(p -> {
                    String n = p.getStructureLabel().name();
                    return (n.equals("BOS_LOW") || n.equals("CHOCH_LOW")) && p.isLow();
                })
                .collect(Collectors.toList());

        List<EnrichedPivot> bosHighs = pivots.stream()
                .filter(p -> p.getStructureLabel() != null)
                .filter(p -> {
                    String n = p.getStructureLabel().name();
                    return (n.equals("BOS_HIGH") || n.equals("CHOCH_HIGH")) && p.isHigh();
                })
                .collect(Collectors.toList());

        result.addAll(buildLines(bosLows, pivots, true, currentBarIndex, currentPrice));
        result.addAll(buildLines(bosHighs, pivots, false, currentBarIndex, currentPrice));
        return result;
    }

    private List<VirginTrendline> buildLines(List<EnrichedPivot> anchors,
                                              List<EnrichedPivot> allPivots,
                                              boolean support,
                                              int currentBarIndex, double currentPrice) {
        List<VirginTrendline> result = new ArrayList<>();
        for (int i = 0; i < anchors.size(); i++) {
            for (int j = i + 1; j < anchors.size(); j++) {
                EnrichedPivot p1 = anchors.get(i);
                EnrichedPivot p2 = anchors.get(j);
                if (p1.getBarIndex() >= p2.getBarIndex()) continue;
                double s = slope(p1, p2);
                double ic = intercept(p1, s);
                if (isVirgin(p1, p2, s, ic, allPivots, support)) {
                    result.add(build(p1, p2, support, 40, name(), currentBarIndex, currentPrice));
                }
            }
        }
        return result;
    }
}
