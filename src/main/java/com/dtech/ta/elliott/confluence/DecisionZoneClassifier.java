package com.dtech.ta.elliott.confluence;

import com.dtech.ta.elliott.WaveHypothesis;
import com.dtech.ta.elliott.WaveScenario;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class DecisionZoneClassifier {

    private static final double INVALIDATION_PROXIMITY_PCT = 0.03;
    private static final double TARGET_PROXIMITY_PCT = 0.02;

    /**
     * Classify high-score zones as DECISION if they align with scenario invalidation levels or targets.
     */
    public List<ConfluenceZone> classify(List<ConfluenceZone> zones,
                                          List<WaveScenario> scenarios,
                                          double minScore) {
        if (zones == null || zones.isEmpty()) return List.of();

        List<WaveHypothesis> allHypotheses = new ArrayList<>();
        if (scenarios != null) {
            for (WaveScenario scenario : scenarios) {
                if (scenario.getHypotheses() != null) {
                    allHypotheses.addAll(scenario.getHypotheses());
                }
            }
        }

        for (ConfluenceZone zone : zones) {
            if (zone.getScore() < minScore) continue;

            List<String> reasons = new ArrayList<>();
            double mid = zone.getMidPrice();

            for (WaveHypothesis h : allHypotheses) {
                double inv = h.getInvalidationLevel();
                if (inv > 0 && isWithinPct(mid, inv, INVALIDATION_PROXIMITY_PCT)) {
                    reasons.add("near_invalidation:" + h.getId());
                }

                if (h.getPrimaryTarget() != null) {
                    double target = h.getPrimaryTarget().getLevel();
                    if (target > 0 && isWithinPct(mid, target, TARGET_PROXIMITY_PCT)) {
                        reasons.add("near_target:" + h.getId());
                    }
                }

                if (h.getTargetZones() != null) {
                    for (WaveHypothesis.FibTarget ft : h.getTargetZones()) {
                        if (ft.getLevel() > 0 && isWithinPct(mid, ft.getLevel(), TARGET_PROXIMITY_PCT)) {
                            reasons.add("near_fib_target:" + ft.getWaveName());
                            break;
                        }
                    }
                }
            }

            if (!reasons.isEmpty()) {
                zone.setZoneType("DECISION");
                if (zone.getExplanation() == null) {
                    zone.setExplanation(new ArrayList<>());
                }
                zone.getExplanation().addAll(reasons);
            }
        }

        return zones;
    }

    private boolean isWithinPct(double a, double b, double pct) {
        return Math.abs(a - b) / Math.max(Math.abs(b), 0.0001) <= pct;
    }
}
