package com.dtech.ta.trendline.scenario;

import com.dtech.ta.elliott.EnrichedPivot;
import com.dtech.ta.trendline.TrendlineScenario;
import com.dtech.ta.trendline.VirginTrendline;
import org.ta4j.core.BarSeries;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Post-scorer: boosts HL→HL trendlines that are aligned with a HL pivot
 * appearing after a BOS_LOW or CHOCH_LOW event (trend reversal confirmation).
 * Bonus: +15 to the trendline score.
 */
public class TrendReversalAlignmentScorer implements TrendlineScenario {

    @Override
    public String name() { return "TrendReversalAlignment"; }

    @Override
    public List<VirginTrendline> detect(List<EnrichedPivot> pivots, BarSeries series) {
        return List.of(); // scorer only — no new candidates
    }

    @Override
    public void score(List<VirginTrendline> allCandidates, List<EnrichedPivot> pivots, BarSeries series) {
        if (pivots == null || allCandidates == null) return;

        // Find the most recent BOS_LOW or CHOCH_LOW bar index (reversal point)
        int reversalBarIndex = pivots.stream()
                .filter(p -> p.getStructureLabel() != null)
                .filter(p -> {
                    String n = p.getStructureLabel().name();
                    return n.equals("BOS_LOW") || n.equals("CHOCH_LOW");
                })
                .mapToInt(EnrichedPivot::getBarIndex)
                .max()
                .orElse(-1);

        if (reversalBarIndex < 0) return;

        // HL pivots that appeared AFTER the reversal
        List<EnrichedPivot> postReversalHLs = pivots.stream()
                .filter(p -> p.getBarIndex() > reversalBarIndex)
                .filter(p -> p.getStructureLabel() != null && p.getStructureLabel().name().equals("HL") && p.isLow())
                .collect(Collectors.toList());

        if (postReversalHLs.isEmpty()) return;

        // Boost support trendlines (HL-HL lines) that pass near any post-reversal HL
        for (VirginTrendline tl : allCandidates) {
            if (!tl.isSupport()) continue;
            for (EnrichedPivot hl : postReversalHLs) {
                double trendlinePrice = tl.getSlope() * hl.getBarIndex() + tl.getIntercept();
                double tolerance = hl.getAtrAtPivot() * 1.0; // 1 ATR tolerance for alignment
                if (Math.abs(hl.getPrice() - trendlinePrice) <= tolerance) {
                    tl.setScore(tl.getScore() + 15);
                    if (!tl.getScenarioTags().contains(name())) {
                        tl.getScenarioTags().add(name());
                    }
                    break;
                }
            }
        }
    }
}
