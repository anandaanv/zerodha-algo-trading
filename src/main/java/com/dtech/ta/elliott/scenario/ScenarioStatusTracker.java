package com.dtech.ta.elliott.scenario;

import com.dtech.ta.elliott.WaveHypothesis;
import com.dtech.ta.elliott.WaveScenario;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class ScenarioStatusTracker {

    private static final double ACTIVE_SCORE_FLOOR = 15.0;
    private static final double WEAK_SCORE_FLOOR   = 5.0;

    /**
     * Evaluate each scenario, assign status, sort by totalScore descending.
     * The highest-scoring non-invalidated scenario gets LEADING.
     */
    public List<ScoredScenario> evaluate(List<WaveScenario> scenarios, double currentPrice) {
        if (scenarios == null || scenarios.isEmpty()) return List.of();

        List<ScoredScenario> result = new ArrayList<>();

        for (WaveScenario scenario : scenarios) {
            List<String> reasons = new ArrayList<>();
            double totalScore = computeTotalScore(scenario);

            // Check hard scenario-level invalidation
            boolean invalidated = isInvalidated(scenario, currentPrice);
            if (invalidated) {
                reasons.add("hard_invalidation: price crossed " + scenario.getScenarioInvalidation());
                result.add(ScoredScenario.builder()
                        .scenario(scenario)
                        .status(ScenarioStatus.INVALIDATED)
                        .totalScore(totalScore)
                        .statusReasons(reasons)
                        .build());
                continue;
            }

            // Hypothesis-level invalidation cascade
            if (scenario.getHypotheses() != null && !scenario.getHypotheses().isEmpty()
                    && currentPrice > 0) {
                boolean anyCascaded = false;
                WaveHypothesis firstValid = null;
                for (WaveHypothesis h : scenario.getHypotheses()) {
                    double inv = h.getInvalidationLevel();
                    if (inv <= 0) {
                        if (firstValid == null) firstValid = h;
                        continue;
                    }
                    boolean hBullish = h.isNextMajorMoveUp();
                    boolean hInvalidated = hBullish ? currentPrice < inv : currentPrice > inv;
                    if (hInvalidated && !h.isHypothesisInvalidated()) {
                        h.setHypothesisInvalidated(true);
                        h.setHypothesisNote("invalidated: price " + String.format("%.2f", currentPrice)
                            + (hBullish ? " < " : " > ") + String.format("%.2f", inv));
                        anyCascaded = true;
                        reasons.add("hypothesis_invalidated: " + h.getId() + " breached at " + String.format("%.2f", inv));
                    } else if (!h.isHypothesisInvalidated() && firstValid == null) {
                        firstValid = h;
                    }
                }
                if (anyCascaded && firstValid != null) {
                    firstValid.setHypothesisNote("promoted_after_cascade");
                    reasons.add("promoted: " + firstValid.getId() + " after cascade");
                }
                // Recompute total score excluding invalidated hypotheses
                totalScore = scenario.getHypotheses().stream()
                    .filter(h -> !h.isHypothesisInvalidated())
                    .mapToDouble(WaveHypothesis::getTotalScore)
                    .boxed()
                    .sorted(Comparator.reverseOrder())
                    .limit(2)
                    .mapToDouble(Double::doubleValue)
                    .sum();
            }

            // Tentative assignment
            ScenarioStatus status;
            if (totalScore >= ACTIVE_SCORE_FLOOR) {
                status = ScenarioStatus.ACTIVE_ALTERNATE;
                reasons.add("score_above_active_floor: " + totalScore);
            } else if (totalScore >= WEAK_SCORE_FLOOR) {
                status = ScenarioStatus.WEAK_ALTERNATE;
                reasons.add("score_above_weak_floor: " + totalScore);
            } else {
                status = ScenarioStatus.AWAITING_TRIGGER;
                reasons.add("score_below_weak_floor: " + totalScore);
            }

            result.add(ScoredScenario.builder()
                    .scenario(scenario)
                    .status(status)
                    .totalScore(totalScore)
                    .statusReasons(reasons)
                    .build());
        }

        // Sort descending by totalScore
        result.sort(Comparator.comparingDouble(ScoredScenario::getTotalScore).reversed());

        // Promote highest-scoring non-invalidated ACTIVE_ALTERNATE to LEADING
        for (ScoredScenario ss : result) {
            if (ss.getStatus() == ScenarioStatus.ACTIVE_ALTERNATE) {
                ss.setStatus(ScenarioStatus.LEADING);
                ss.getStatusReasons().add("highest_score_non_invalidated");
                break;
            }
        }

        return result;
    }

    private double computeTotalScore(WaveScenario scenario) {
        if (scenario.getHypotheses() == null || scenario.getHypotheses().isEmpty()) return 0.0;
        return scenario.getHypotheses().stream()
                .mapToDouble(WaveHypothesis::getTotalScore)
                .boxed()
                .sorted(Comparator.reverseOrder())
                .limit(2)
                .mapToDouble(Double::doubleValue)
                .sum();
    }

    private boolean isInvalidated(WaveScenario scenario, double currentPrice) {
        double inv = scenario.getScenarioInvalidation();
        if (inv <= 0) return false;

        WaveScenario.ScenarioDirection dir = scenario.getDirection();
        if (dir == null) return false;

        boolean bullish = dir == WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                || dir == WaveScenario.ScenarioDirection.BULLISH_REVERSAL;

        // Bullish scenario: invalidated if price drops below invalidation
        // Bearish scenario: invalidated if price rises above invalidation
        if (bullish) {
            return currentPrice < inv;
        } else {
            return currentPrice > inv;
        }
    }
}
