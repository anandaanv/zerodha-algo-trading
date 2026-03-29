package com.dtech.kitecon.analysis;

import com.dtech.ta.elliott.GapLevel;
import com.dtech.ta.elliott.NestedWaveContext;
import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.PatternStatus;
import com.dtech.ta.elliott.WaveHypothesis;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.WaveScenario.ScenarioDirection;
import com.dtech.kitecon.analysis.dto.NormalizedScenario;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ScoreNormalizer {

    /**
     * Normalizes WaveScenario scores with pattern confluence, context alignment, and gap penalties.
     *
     * @param scenarios          List of wave scenarios to normalize
     * @param dedupedPatterns    List of deduplicated pattern matches for confluence boosts
     * @param nestedContexts     List of nested wave contexts for alignment boosts
     * @param significantGaps    List of significant gap levels for penalties
     * @return List of normalized scenarios sorted by normalized percentage descending
     */
    public List<NormalizedScenario> normalize(
            List<WaveScenario> scenarios,
            List<PatternMatch> dedupedPatterns,
            List<NestedWaveContext> nestedContexts,
            List<GapLevel> significantGaps) {

        if (scenarios == null || scenarios.isEmpty()) {
            return List.of();
        }

        // Step 1: Initialize scores from raw scenario data
        Map<WaveScenario, Double> adjustedScores = scenarios.stream()
                .collect(Collectors.toMap(
                        scenario -> scenario,
                        scenario -> calculateBaseScore(scenario)
                ));

        // Step 2: Apply pattern confluence boosts
        if (dedupedPatterns != null && !dedupedPatterns.isEmpty()) {
            applyPatternConfluenceBoosts(adjustedScores, scenarios, dedupedPatterns);
        }

        // Step 3: Apply nested context alignment boosts
        if (nestedContexts != null && !nestedContexts.isEmpty()) {
            applyNestedContextBoosts(adjustedScores, scenarios, nestedContexts);
        }

        // Step 4: Apply gap zone penalties
        if (significantGaps != null && !significantGaps.isEmpty()) {
            applyGapPenalties(adjustedScores, scenarios, significantGaps);
        }

        // Step 5: Normalize to percentages using raw scores (not adjusted) — preserves correct ranking
        double totalRawScore = scenarios.stream()
                .mapToDouble(this::calculateBaseScore)
                .sum();

        List<NormalizedScenario> normalized = new ArrayList<>();
        for (int i = 0; i < scenarios.size(); i++) {
            WaveScenario scenario = scenarios.get(i);
            double rawScore = calculateBaseScore(scenario);
            double normalizedPct = (totalRawScore > 0) ? (rawScore / totalRawScore) * 100.0 : 0.0;

            NormalizedScenario normScenario = new NormalizedScenario();
            normScenario.setId(scenario.getId());
            normScenario.setLabel(scenario.getDirectionLabel());
            normScenario.setDirectionLabel(scenario.getDirectionLabel());
            normScenario.setRawScore((int) rawScore);
            normScenario.setNormalizedPct(normalizedPct);
            normScenario.setLeading(false); // Will be set after sorting
            normScenario.setScenarioInvalidation(scenario.getScenarioInvalidation());
            normScenario.setBias(determineBias(scenario.getDirection()));
            normScenario.setTarget(extractTarget(scenario));

            normalized.add(normScenario);
        }

        // Step 6: Sort by normalized percentage descending
        normalized.sort(Comparator.comparingDouble(NormalizedScenario::getNormalizedPct).reversed());

        // Step 7: Mark the leading scenario
        if (!normalized.isEmpty()) {
            normalized.get(0).setLeading(true);
        }

        return normalized;
    }

    /**
     * Calculates base score from scenario hypotheses count.
     *
     * @param scenario The wave scenario
     * @return Base score (hypotheses count * 10, or 30 if empty)
     */
    private double calculateBaseScore(WaveScenario scenario) {
        WaveHypothesis best = scenario.highestScoringHypothesis();
        if (best != null && best.getTotalScore() > 0) {
            return best.getTotalScore();
        }
        return 30.0;
    }

    /**
     * Applies pattern confluence boosts to scenario scores.
     * WATCHING patterns boost matching directional scenarios.
     *
     * @param adjustedScores Map of scenarios to adjusted scores
     * @param scenarios      List of all scenarios
     * @param patterns       List of pattern matches
     */
    private void applyPatternConfluenceBoosts(
            Map<WaveScenario, Double> adjustedScores,
            List<WaveScenario> scenarios,
            List<PatternMatch> patterns) {

        for (PatternMatch pattern : patterns) {
            if (pattern.getStatus() != PatternStatus.WATCHING) {
                continue;
            }

            double boostAmount = 5.0;
            double maxBoost = 15.0;

            for (WaveScenario scenario : scenarios) {
                double currentBoost = 0.0;

                if (pattern.isBullish()) {
                    // Boost BULLISH scenarios
                    if (scenario.getDirection() == ScenarioDirection.BULLISH_CONTINUATION ||
                            scenario.getDirection() == ScenarioDirection.BULLISH_REVERSAL) {
                        currentBoost = boostAmount;
                    }
                } else if (pattern.isBearish()) {
                    // Boost BEARISH scenarios
                    if (scenario.getDirection() == ScenarioDirection.BEARISH_CONTINUATION ||
                            scenario.getDirection() == ScenarioDirection.BEARISH_REVERSAL) {
                        currentBoost = boostAmount;
                    }
                } else {
                    // Neutral patterns (SYMMETRICAL_TRIANGLE, HORIZONTAL_CHANNEL) boost RANGE_RESOLUTION
                    if (scenario.getDirection() == ScenarioDirection.RANGE_RESOLUTION) {
                        currentBoost = boostAmount;
                    }
                }

                if (currentBoost > 0) {
                    double newScore = adjustedScores.get(scenario) + currentBoost;
                    // Cap at +15 total per scenario
                    newScore = Math.min(newScore, calculateBaseScore(scenario) + maxBoost);
                    adjustedScores.put(scenario, newScore);
                }
            }
        }
    }

    /**
     * Applies nested context alignment boosts to scenario scores.
     * The branch with highest probability determines which directional scenarios get boosted.
     *
     * @param adjustedScores Map of scenarios to adjusted scores
     * @param scenarios      List of all scenarios
     * @param contexts       List of nested wave contexts
     */
    private void applyNestedContextBoosts(
            Map<WaveScenario, Double> adjustedScores,
            List<WaveScenario> scenarios,
            List<NestedWaveContext> contexts) {

        final double CONTEXT_BOOST = 10.0;
        boolean boostApplied = false;

        for (NestedWaveContext context : contexts) {
            if (boostApplied) {
                break; // Apply at most once
            }

            if (context.getBranches() == null || context.getBranches().isEmpty()) {
                continue;
            }

            // Find branch with highest probability
            NestedWaveContext.BranchHypothesis highestProbBranch = context.getBranches().stream()
                    .max(Comparator.comparingDouble(NestedWaveContext.BranchHypothesis::getProbability))
                    .orElse(null);

            if (highestProbBranch == null) {
                continue;
            }

            String nextMove = highestProbBranch.getNextHigherDegreeMove();
            if (nextMove == null) {
                continue;
            }

            String moveUpper = nextMove.toLowerCase();
            ScenarioDirection boostDirection = null;

            if (moveUpper.contains("bull") || moveUpper.contains("up")) {
                boostDirection = ScenarioDirection.BULLISH_CONTINUATION;
            } else if (moveUpper.contains("bear") || moveUpper.contains("down")) {
                boostDirection = ScenarioDirection.BEARISH_CONTINUATION;
            } else {
                boostDirection = ScenarioDirection.RANGE_RESOLUTION;
            }

            // Apply boost to matching scenarios
            for (WaveScenario scenario : scenarios) {
                ScenarioDirection scenarioDir = scenario.getDirection();
                boolean shouldBoost = false;

                if (boostDirection == ScenarioDirection.BULLISH_CONTINUATION) {
                    shouldBoost = scenarioDir == ScenarioDirection.BULLISH_CONTINUATION ||
                            scenarioDir == ScenarioDirection.BULLISH_REVERSAL;
                } else if (boostDirection == ScenarioDirection.BEARISH_CONTINUATION) {
                    shouldBoost = scenarioDir == ScenarioDirection.BEARISH_CONTINUATION ||
                            scenarioDir == ScenarioDirection.BEARISH_REVERSAL;
                } else if (boostDirection == ScenarioDirection.RANGE_RESOLUTION) {
                    shouldBoost = scenarioDir == ScenarioDirection.RANGE_RESOLUTION;
                }

                if (shouldBoost) {
                    adjustedScores.put(scenario, adjustedScores.get(scenario) + CONTEXT_BOOST);
                    boostApplied = true;
                }
            }
        }
    }

    /**
     * Applies gap zone penalties to scenario scores.
     * SIGNIFICANT unfilled gaps penalize bullish scenarios that would cross them.
     *
     * @param adjustedScores Map of scenarios to adjusted scores
     * @param scenarios      List of all scenarios
     * @param gaps           List of gap levels
     */
    private void applyGapPenalties(
            Map<WaveScenario, Double> adjustedScores,
            List<WaveScenario> scenarios,
            List<GapLevel> gaps) {

        final double GAP_PENALTY = -5.0;

        // Find any SIGNIFICANT unfilled GAP_DOWN gaps
        boolean hasUnfilledSignificantGap = gaps.stream()
                .anyMatch(gap -> gap.getDirection() == GapLevel.GapDirection.GAP_DOWN &&
                        gap.getFillStatus() == GapLevel.GapFillStatus.UNFILLED &&
                        gap.getSignificance() == GapLevel.GapSignificance.SIGNIFICANT);

        if (!hasUnfilledSignificantGap) {
            return;
        }

        // Apply penalty to bullish scenarios
        for (WaveScenario scenario : scenarios) {
            ScenarioDirection direction = scenario.getDirection();
            if (direction == ScenarioDirection.BULLISH_CONTINUATION ||
                    direction == ScenarioDirection.BULLISH_REVERSAL) {
                adjustedScores.put(scenario, adjustedScores.get(scenario) + GAP_PENALTY);
            }
        }
    }

    /**
     * Determines bias (BULL, BEAR, NEUTRAL) from scenario direction.
     *
     * @param direction The scenario direction
     * @return Bias string
     */
    private String determineBias(ScenarioDirection direction) {
        if (direction == ScenarioDirection.BULLISH_CONTINUATION ||
                direction == ScenarioDirection.BULLISH_REVERSAL) {
            return "BULL";
        } else if (direction == ScenarioDirection.BEARISH_CONTINUATION ||
                direction == ScenarioDirection.BEARISH_REVERSAL) {
            return "BEAR";
        } else {
            return "NEUTRAL";
        }
    }

    /**
     * Extracts target price from scenario's first hypothesis primary target.
     *
     * @param scenario The wave scenario
     * @return Target price or null if not available
     */
    private Double extractTarget(WaveScenario scenario) {
        if (scenario.getHypotheses() == null || scenario.getHypotheses().isEmpty()) {
            return null;
        }

        var hypothesis = scenario.getHypotheses().get(0);
        if (hypothesis.getPrimaryTarget() != null) {
            return hypothesis.getPrimaryTarget().getLevel();
        }

        return null;
    }
}
