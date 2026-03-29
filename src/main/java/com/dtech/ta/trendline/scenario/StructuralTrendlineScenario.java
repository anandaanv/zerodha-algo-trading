package com.dtech.ta.trendline.scenario;

import com.dtech.ta.elliott.EnrichedPivot;
import com.dtech.ta.trendline.TrendlineScenario;
import com.dtech.ta.trendline.VirginTrendline;
import org.ta4j.core.BarSeries;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Connects HL→HL (support) and LH→LH (resistance) structural pivots.
 * These represent continuation points in an established trend.
 * Weight: 25.
 */
public class StructuralTrendlineScenario implements TrendlineScenario {

    @Override
    public String name() { return "Structural"; }

    @Override
    public List<VirginTrendline> detect(List<EnrichedPivot> pivots, BarSeries series) {
        List<VirginTrendline> result = new ArrayList<>();
        if (pivots == null || pivots.size() < 2 || series == null) return result;

        int currentBarIndex = series.getBarCount() - 1;
        double currentPrice = series.getBar(currentBarIndex).getClosePrice().doubleValue();

        // Uptrend: HL→HL (support) and HH→HH (resistance)
        List<EnrichedPivot> hls = pivots.stream()
                .filter(p -> p.getStructureLabel() != null && p.getStructureLabel().name().equals("HL") && p.isLow())
                .collect(Collectors.toList());

        List<EnrichedPivot> hhs = pivots.stream()
                .filter(p -> p.getStructureLabel() != null && p.getStructureLabel().name().equals("HH") && p.isHigh())
                .collect(Collectors.toList());

        // Downtrend: LH→LH (resistance) and LL→LL (support)
        List<EnrichedPivot> lhs = pivots.stream()
                .filter(p -> p.getStructureLabel() != null && p.getStructureLabel().name().equals("LH") && p.isHigh())
                .collect(Collectors.toList());

        List<EnrichedPivot> lls = pivots.stream()
                .filter(p -> p.getStructureLabel() != null && p.getStructureLabel().name().equals("LL") && p.isLow())
                .collect(Collectors.toList());

        result.addAll(buildLines(hls, pivots, true, currentBarIndex, currentPrice));
        result.addAll(buildLines(hhs, pivots, false, currentBarIndex, currentPrice));
        result.addAll(buildLines(lhs, pivots, false, currentBarIndex, currentPrice));
        result.addAll(buildLines(lls, pivots, true, currentBarIndex, currentPrice));
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
                    result.add(build(p1, p2, support, 25, name(), currentBarIndex, currentPrice));
                }
            }
        }
        return result;
    }
}
