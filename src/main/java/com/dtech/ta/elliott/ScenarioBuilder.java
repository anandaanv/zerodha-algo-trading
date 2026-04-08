package com.dtech.ta.elliott;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Layer 7: Builds WaveScenarios from wave counts and detected patterns.
 *
 * This is the final coded layer before AI:
 * 1. Applies hard rule elimination (invalid counts dropped)
 * 2. Computes fuzzy scores (Fibonacci + indicator + cross-TF + alternation)
 * 3. Groups valid counts into directional scenarios
 * 4. For each hypothesis, computes targets, invalidation, expected patterns, evidence
 * 5. Identifies shared decision points within each scenario
 * 6. Cross-references detected patterns with wave position expectations
 *
 * DESIGN PRINCIPLE: No conclusions. Every possibility is presented.
 * The AI layer reads these scenarios and makes the final judgment.
 */
@Service
public class ScenarioBuilder {

    /**
     * Build all scenarios from wave counts and detected patterns.
     *
     * @param waveCounts all valid wave counts from WaveCounter
     * @param patterns   all detected/building patterns from PivotPatternDetector
     * @param timeframe  the primary timeframe being analyzed
     * @return all scenarios with hypotheses, targets, and evidence
     */
    public List<WaveScenario> build(List<WaveCount> waveCounts, List<PatternMatch> patterns, String timeframe, double currentPrice) {
        // Group wave counts by directional thesis
        Map<WaveScenario.ScenarioDirection, List<WaveCount>> grouped = groupByDirection(waveCounts);

        List<WaveScenario> scenarios = new ArrayList<>();

        for (Map.Entry<WaveScenario.ScenarioDirection, List<WaveCount>> entry : grouped.entrySet()) {
            WaveScenario.ScenarioDirection direction = entry.getKey();
            List<WaveCount> counts = entry.getValue();

            // Build hypotheses for each count in this direction
            List<WaveHypothesis> hypotheses = counts.stream()
                    .map(wc -> buildHypothesis(wc, patterns, direction, currentPrice))
                    .sorted(Comparator
                        .<WaveHypothesis>comparingInt(h -> activationRank(h.getActivationStatus()))
                        .thenComparingInt(h -> -h.getTotalScore()))
                    .toList();

            if (hypotheses.isEmpty()) continue;

            // Compute scenario-level invalidation (the most lenient — if price breaks this, all are wrong)
            double scenarioInvalidation = computeScenarioInvalidation(direction, counts);

            // Find patterns relevant to this scenario direction
            List<PatternMatch> aligned    = findAlignedPatterns(direction, patterns);
            List<PatternMatch> conflicting = findConflictingPatterns(direction, patterns);
            List<PatternMatch> allRelevant = new ArrayList<>();
            allRelevant.addAll(aligned);
            allRelevant.addAll(conflicting);

            // Decision points shared across hypotheses in this scenario
            List<WaveScenario.DecisionPoint> decisionPoints = buildDecisionPoints(hypotheses, direction);

            // Indicator alignment summary
            Map<String, String> indicatorAlignment = buildIndicatorSummary(counts, direction);

            String id = "S" + (scenarios.size() + 1);
            scenarios.add(WaveScenario.builder()
                    .id(id)
                    .direction(direction)
                    .directionLabel(directionLabel(direction))
                    .hypotheses(hypotheses)
                    .decisionPoints(decisionPoints)
                    .scenarioInvalidation(scenarioInvalidation)
                    .invalidationReason(invalidationReason(direction, scenarioInvalidation))
                    .relevantPatterns(allRelevant)
                    .alignedPatterns(aligned)
                    .conflictingPatterns(conflicting)
                    .indicatorAlignment(indicatorAlignment)
                    .build());
        }

        // Sort scenarios: highest combined hypothesis score first
        scenarios.sort(Comparator.comparingInt(s ->
                -s.getHypotheses().stream().mapToInt(WaveHypothesis::getTotalScore).max().orElse(0)));

        return scenarios;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DIRECTION GROUPING
    // ══════════════════════════════════════════════════════════════════════════

    private Map<WaveScenario.ScenarioDirection, List<WaveCount>> groupByDirection(List<WaveCount> counts) {
        Map<WaveScenario.ScenarioDirection, List<WaveCount>> map = new LinkedHashMap<>();

        for (WaveCount wc : counts) {
            WaveScenario.ScenarioDirection dir = inferDirection(wc);
            map.computeIfAbsent(dir, k -> new ArrayList<>()).add(wc);
        }
        return map;
    }

    private WaveScenario.ScenarioDirection inferDirection(WaveCount wc) {
        WaveLabel cur = wc.getCurrentWaveInProgress();
        return switch (wc.getWaveType()) {
            case IMPULSE -> switch (cur) {
                case W2, W3, W4 -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                        : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
                case W5         -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BEARISH_REVERSAL
                        : WaveScenario.ScenarioDirection.BULLISH_REVERSAL;
                case UNKNOWN    -> {
                    String desc = wc.getCurrentPositionDescription();
                    if (desc != null && desc.contains("Post-impulse")) {
                        yield wc.isBullish()
                                ? WaveScenario.ScenarioDirection.BEARISH_CONTINUATION
                                : WaveScenario.ScenarioDirection.BULLISH_CONTINUATION;
                    }
                    yield wc.isBullish()
                            ? WaveScenario.ScenarioDirection.BEARISH_REVERSAL
                            : WaveScenario.ScenarioDirection.BULLISH_REVERSAL;
                }
                default -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                        : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
            };
            case ZIGZAG, FLAT, EXPANDED_FLAT, RUNNING_FLAT -> switch (cur) {
                case WA, WB -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                        : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
                case WC, UNKNOWN -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BEARISH_REVERSAL
                        : WaveScenario.ScenarioDirection.BULLISH_REVERSAL;
                default -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                        : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
            };
            case TRIANGLE         -> WaveScenario.ScenarioDirection.RANGE_RESOLUTION;
            case LEADING_DIAGONAL -> switch (cur) {
                case W3, W4 -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                        : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
                case W5, UNKNOWN -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BEARISH_REVERSAL
                        : WaveScenario.ScenarioDirection.BULLISH_REVERSAL;
                default -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                        : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
            };
            case ENDING_DIAGONAL -> switch (cur) {
                case W4, W5 -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                        : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
                case UNKNOWN -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BEARISH_REVERSAL
                        : WaveScenario.ScenarioDirection.BULLISH_REVERSAL;
                default -> wc.isBullish()
                        ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                        : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
            };
            case DOUBLE_ZIGZAG, COMPLEX_WXY -> wc.isBullish()
                    ? WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                    : WaveScenario.ScenarioDirection.BEARISH_CONTINUATION;
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HYPOTHESIS BUILDING
    // ══════════════════════════════════════════════════════════════════════════

    private WaveHypothesis buildHypothesis(WaveCount wc, List<PatternMatch> allPatterns,
                                            WaveScenario.ScenarioDirection direction, double currentPrice) {
        List<WaveHypothesis.FibTarget> targets = computeTargets(wc);
        double invalidation = computeInvalidation(wc);
        List<PatternType> expected = expectedPatternsForWave(wc.getCurrentWaveInProgress(), direction);
        List<PatternMatch> matched = matchPatterns(allPatterns, expected);

        // Pattern confluence bonus: forward match + reverse WaveContextHint inference
        // Use local mutable lists — never mutate the WaveCount's (possibly immutable) lists
        List<WaveEvidence> supporting    = new ArrayList<>(wc.getSupportingEvidence() != null ? wc.getSupportingEvidence() : List.of());
        List<WaveEvidence> contradicting = new ArrayList<>(wc.getContradictingEvidence() != null ? wc.getContradictingEvidence() : List.of());

        int patternBonus = 0;
        Set<String> contradictingKeys = new java.util.LinkedHashSet<>(); // dedup by type+tf
        for (PatternMatch pm : allPatterns) {
            // Only process patterns from the same timeframe as this wave count
            // (cross-TF patterns produce noise: every pattern's hints contradict every other TF's wave)
            if (!wc.getPrimaryTimeframe().equals(pm.getTimeframe())) continue;

            // Forward: expected pattern for this wave position
            if (expected.contains(pm.getType())) {
                patternBonus += pm.getStatus() == PatternStatus.CONFIRMED ? 15
                              : pm.getStatus() == PatternStatus.WATCHING  ? 10 : 5;
            }
            // Reverse: WaveContextHint agrees with current wave count
            for (WaveContextHint hint : pm.getWaveContextHints()) {
                if (hint.getImpliedCurrentPosition() == wc.getCurrentWaveInProgress()) {
                    patternBonus += (int)(hint.getProbability() * 20);
                    supporting.add(WaveEvidence.supports("Pattern-Wave",
                        pm.getType() + "@" + pm.getTimeframe() + " implies "
                        + hint.getImpliedCurrentPosition() + ": " + hint.getNarrative()));
                } else if (hint.getProbability() > 0.6) {
                    // Deduplicate contradicting evidence by pattern type+position
                    String key = pm.getType() + "|" + hint.getImpliedCurrentPosition();
                    if (contradictingKeys.add(key)) {
                        contradicting.add(WaveEvidence.contradicts("Pattern-Wave",
                            pm.getType() + "@" + pm.getTimeframe() + " implies "
                            + hint.getImpliedCurrentPosition() + " not " + wc.getCurrentWaveInProgress(),
                            hint.getProbability()));
                    }
                }
            }
        }
        int total = wc.totalScore() + Math.min(patternBonus, 25);

        Map<String, Integer> scoreBreakdown = new LinkedHashMap<>();
        scoreBreakdown.put("fibonacci",       wc.getFibonacciScore());
        scoreBreakdown.put("indicators",      wc.getIndicatorScore());
        scoreBreakdown.put("crossTf",         wc.getCrossTfScore());
        scoreBreakdown.put("alternation",     wc.getAlternationScore());
        scoreBreakdown.put("patternConfluence", Math.min(patternBonus, 25));
        scoreBreakdown.put("proportionality", wc.getProportionalityBonus());

        boolean nextUp = direction == WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                || direction == WaveScenario.ScenarioDirection.BULLISH_REVERSAL;

        Double decisionLevel = computeDecisionLevel(wc);
        String decisionMeaning = decisionLevel != null ? describeDecisionLevel(wc, decisionLevel) : null;

        // Activation status: how far is the decision level from current price?
        String activationStatus = "DISTANT";
        Double distanceFromPrice = null;
        if (decisionLevel != null && currentPrice > 0) {
            double dist = Math.abs(decisionLevel - currentPrice) / currentPrice;
            distanceFromPrice = dist;
            if (dist <= 0.03)       activationStatus = "ACTIVE";
            else if (dist <= 0.15)  activationStatus = "WATCHING";
            else                    activationStatus = "DISTANT";
        }

        // Override for historical wave counts
        if (wc.isHistorical()) {
            activationStatus = "HISTORICAL";
        }

        return WaveHypothesis.builder()
                .id("H" + UUID.randomUUID().toString().substring(0, 4))
                .waveCount(wc)
                .currentPositionDescription(wc.getCurrentPositionDescription())
                .nextMajorMoveUp(nextUp)
                .targetZones(targets)
                .primaryTarget(targets.isEmpty() ? null : targets.get(0))
                .invalidationLevel(invalidation)
                .invalidationRule(describeInvalidation(wc, invalidation))
                .decisionLevel(decisionLevel)
                .decisionLevelMeaning(decisionMeaning)
                .expectedPatterns(expected)
                .matchedPatterns(matched)
                .indicatorSummary(buildPivotIndicatorSummary(wc))
                .supportingEvidence(supporting)
                .contradictingEvidence(contradicting)
                .totalScore(total)
                .scoreBreakdown(scoreBreakdown)
                .activationStatus(activationStatus)
                .distanceFromCurrentPrice(distanceFromPrice)
                .build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // FIBONACCI TARGETS
    // ══════════════════════════════════════════════════════════════════════════

    private List<WaveHypothesis.FibTarget> computeTargets(WaveCount wc) {
        List<WaveHypothesis.FibTarget> targets = new ArrayList<>();
        List<EnrichedPivot> pivots = wc.getPivots();
        if (pivots == null || pivots.size() < 2) return targets;

        switch (wc.getWaveType()) {
            case IMPULSE -> {
                if (wc.getCurrentWaveInProgress() == WaveLabel.W5 && pivots.size() >= 5) {
                    EnrichedPivot w4 = pivots.get(4);
                    EnrichedPivot w1 = pivots.get(1);
                    EnrichedPivot w1s = pivots.get(0);
                    double w1Length = w1.getPrice() - w1s.getPrice();
                    // W5 = 61.8% of W1
                    targets.add(target(w4.getPrice() + w1Length * 0.618, "W5 = 61.8% of W1", "W5", 0.4));
                    // W5 = 100% of W1
                    targets.add(target(w4.getPrice() + w1Length, "W5 = 100% of W1", "W5", 0.4));
                    // W5 = 161.8% of W1
                    targets.add(target(w4.getPrice() + w1Length * 1.618, "W5 = 161.8% of W1", "W5", 0.2));
                } else if (wc.getCurrentWaveInProgress() == WaveLabel.W4 && pivots.size() >= 4) {
                    EnrichedPivot w3 = pivots.get(3);
                    EnrichedPivot w2 = pivots.get(2);
                    double w3Length = w3.getPrice() - w2.getPrice();
                    // W4 typically retraces 38.2% of W3
                    targets.add(target(w3.getPrice() - w3Length * 0.382, "W4 = 38.2% retrace of W3", "W4", 0.5));
                    targets.add(target(w3.getPrice() - w3Length * 0.500, "W4 = 50.0% retrace of W3", "W4", 0.3));
                    targets.add(target(w3.getPrice() - w3Length * 0.618, "W4 = 61.8% retrace of W3", "W4", 0.2));
                } else if (wc.getCurrentWaveInProgress() == WaveLabel.UNKNOWN && pivots.size() >= 5) {
                    // Completed impulse — compute correction/continuation targets
                    EnrichedPivot impulseStart = pivots.get(0);
                    EnrichedPivot impulseEnd   = pivots.get(pivots.size() - 1);
                    EnrichedPivot w4Pivot      = pivots.get(pivots.size() - 2); // W4 end = last corrective pivot
                    if (wc.isBullish()) {
                        // Completed bullish impulse → expect bearish correction
                        // Rule 1: W4 low (standard retrace zone)
                        targets.add(target(w4Pivot.getPrice(),
                                "W4 low — standard correction zone", "Correction", 0.5));
                        // Rule 2: 0.618 retrace of full impulse
                        double retrace618 = impulseEnd.getPrice()
                                - 0.618 * (impulseEnd.getPrice() - impulseStart.getPrice());
                        targets.add(target(retrace618,
                                "0.618 retracement of full impulse", "Correction", 0.3));
                        // Rule 3: 0.5 retrace (minimum)
                        double retrace50 = impulseEnd.getPrice()
                                - 0.5 * (impulseEnd.getPrice() - impulseStart.getPrice());
                        targets.add(target(retrace50,
                                "0.5 retracement of full impulse", "Correction", 0.2));
                    } else {
                        // Completed bearish impulse → continuation / measured move
                        double impulseLen = impulseStart.getPrice() - impulseEnd.getPrice();
                        targets.add(target(impulseEnd.getPrice() - impulseLen,
                                "Measured move extension (100% of bearish impulse)", "Extension", 0.4));
                        targets.add(target(impulseEnd.getPrice() - impulseLen * 0.618,
                                "0.618 extension of bearish impulse", "Extension", 0.4));
                        targets.add(target(impulseEnd.getPrice() - impulseLen * 1.618,
                                "1.618 extension of bearish impulse", "Extension", 0.2));
                    }
                }
            }
            case ZIGZAG -> {
                if (wc.getCurrentWaveInProgress() == WaveLabel.WC && pivots.size() >= 3) {
                    EnrichedPivot wA_start = pivots.get(0);
                    EnrichedPivot wA_end   = pivots.get(1);
                    EnrichedPivot wB_end   = pivots.get(2);
                    double aLength = Math.abs(wA_start.getPrice() - wA_end.getPrice());
                    boolean down = wA_end.getPrice() < wA_start.getPrice();
                    double dir = down ? -1 : 1;
                    targets.add(target(wB_end.getPrice() + dir * aLength * 1.0, "WC = 100% of WA", "WC", 0.4));
                    targets.add(target(wB_end.getPrice() + dir * aLength * 1.618, "WC = 161.8% of WA", "WC", 0.4));
                    targets.add(target(wB_end.getPrice() + dir * aLength * 0.618, "WC = 61.8% of WA", "WC", 0.2));
                } else if (wc.getCurrentWaveInProgress() == WaveLabel.WB && pivots.size() >= 2) {
                    EnrichedPivot wA_start = pivots.get(0);
                    EnrichedPivot wA_end   = pivots.get(1);
                    double aLength = Math.abs(wA_start.getPrice() - wA_end.getPrice());
                    boolean waDown = wA_end.getPrice() < wA_start.getPrice();
                    double retDir = waDown ? 1 : -1; // WB retraces opposite direction of WA
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 0.618, "WB = 61.8% retrace of WA", "WB", 0.4));
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 0.500, "WB = 50% retrace of WA", "WB", 0.35));
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 0.382, "WB = 38.2% retrace of WA", "WB", 0.25));
                }
            }
            case EXPANDED_FLAT -> {
                if (wc.getCurrentWaveInProgress() == WaveLabel.WC && pivots.size() >= 3) {
                    EnrichedPivot wA_start = pivots.get(0);
                    EnrichedPivot wA_end   = pivots.get(1);
                    EnrichedPivot wB_end   = pivots.get(2);
                    double aLength = Math.abs(wA_start.getPrice() - wA_end.getPrice());
                    boolean down = wA_end.getPrice() < wA_start.getPrice();
                    double dir = down ? -1 : 1;
                    // Expanded flat: WC typically reaches 127.2% or 161.8% of WA from WB end
                    targets.add(target(wB_end.getPrice() + dir * aLength * 1.272, "WC = 127.2% of WA (expanded flat primary)", "WC", 0.5));
                    targets.add(target(wB_end.getPrice() + dir * aLength * 1.618, "WC = 161.8% of WA (expanded flat extended)", "WC", 0.35));
                    targets.add(target(wA_end.getPrice(), "WC returns to WA end level (minimum)", "WC", 0.15));
                } else if (wc.getCurrentWaveInProgress() == WaveLabel.WB && pivots.size() >= 2) {
                    EnrichedPivot wA_start = pivots.get(0);
                    EnrichedPivot wA_end   = pivots.get(1);
                    double aLength = Math.abs(wA_start.getPrice() - wA_end.getPrice());
                    boolean waDown = wA_end.getPrice() < wA_start.getPrice();
                    double retDir = waDown ? 1 : -1;
                    // Expanded flat B retraces 100%+ of A — use higher targets
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 1.0,   "WB = 100% of WA (expanded flat min)", "WB", 0.4));
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 1.272, "WB = 127.2% of WA (expanded flat typical)", "WB", 0.4));
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 1.618, "WB = 161.8% of WA (expanded flat extended)", "WB", 0.2));
                }
            }
            case FLAT, RUNNING_FLAT -> {
                if (wc.getCurrentWaveInProgress() == WaveLabel.WC && pivots.size() >= 3) {
                    EnrichedPivot wA_start = pivots.get(0);
                    EnrichedPivot wA_end   = pivots.get(1);
                    EnrichedPivot wB_end   = pivots.get(2);
                    double aLength = Math.abs(wA_start.getPrice() - wA_end.getPrice());
                    boolean down = wA_end.getPrice() < wA_start.getPrice();
                    double dir = down ? -1 : 1;
                    targets.add(target(wB_end.getPrice() + dir * aLength, "WC = 100% of WA (flat)", "WC", 0.5));
                    targets.add(target(wA_end.getPrice(), "WC returns to WA end level", "WC", 0.3));
                } else if (wc.getCurrentWaveInProgress() == WaveLabel.WB && pivots.size() >= 2) {
                    EnrichedPivot wA_start = pivots.get(0);
                    EnrichedPivot wA_end   = pivots.get(1);
                    double aLength = Math.abs(wA_start.getPrice() - wA_end.getPrice());
                    boolean waDown = wA_end.getPrice() < wA_start.getPrice();
                    double retDir = waDown ? 1 : -1;
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 0.618, "WB = 61.8% retrace of WA", "WB", 0.4));
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 0.500, "WB = 50% retrace of WA", "WB", 0.35));
                    targets.add(target(wA_end.getPrice() + retDir * aLength * 0.382, "WB = 38.2% retrace of WA", "WB", 0.25));
                }
            }
            case TRIANGLE -> {
                // Triangle target = pole height (Wave A length) projected from WE breakout point
                EnrichedPivot last = pivots.get(pivots.size() - 1);
                if (pivots.size() >= 2) {
                    double poleHeight = Math.abs(pivots.get(1).getPrice() - pivots.get(0).getPrice());
                    boolean thrustUp = wc.isBullish();
                    double bp = last.getPrice();
                    targets.add(target(
                        thrustUp ? bp + poleHeight : bp - poleHeight,
                        "Triangle thrust = Wave A pole height from WE", "Thrust", 0.5));
                    targets.add(target(
                        thrustUp ? bp + poleHeight * 0.618 : bp - poleHeight * 0.618,
                        "Triangle thrust = 61.8% of Wave A (conservative)", "Thrust", 0.35));
                } else {
                    targets.add(target(last.getPrice() * 1.10, "Triangle breakout estimate", "WE", 0.5));
                }
            }
            case LEADING_DIAGONAL -> {
                // Leading diagonal in W1 position: expect W2 to retrace 78.6%–100% of diagonal
                if (pivots.size() >= 2) {
                    EnrichedPivot w1Start = pivots.get(0);
                    EnrichedPivot w1End   = pivots.get(pivots.size() - 1);
                    double diagLen = Math.abs(w1End.getPrice() - w1Start.getPrice());
                    boolean bullish = wc.isBullish();
                    // W2 retrace targets from W1 end
                    targets.add(target(
                        bullish ? w1End.getPrice() - diagLen * 0.786 : w1End.getPrice() + diagLen * 0.786,
                        "W2 = 78.6% retrace of leading diagonal", "W2", 0.5));
                    targets.add(target(
                        bullish ? w1End.getPrice() - diagLen * 1.0   : w1End.getPrice() + diagLen * 1.0,
                        "W2 = 100% retrace of leading diagonal (full retrace)", "W2", 0.35));
                    targets.add(target(
                        bullish ? w1End.getPrice() - diagLen * 0.618 : w1End.getPrice() + diagLen * 0.618,
                        "W2 = 61.8% retrace of leading diagonal (shallow)", "W2", 0.15));
                }
            }
            case ENDING_DIAGONAL -> {
                // Ending diagonal in W5/WC position: sharp reversal expected back to diagonal origin (W1 start)
                if (pivots.size() >= 2) {
                    EnrichedPivot w1Start = pivots.get(0);
                    EnrichedPivot w1End   = pivots.get(pivots.size() - 1);
                    double diagLen = Math.abs(w1End.getPrice() - w1Start.getPrice());
                    // Primary target: full retrace back to diagonal origin
                    targets.add(target(w1Start.getPrice(),
                        "Ending diagonal reversal target = diagonal origin (W1 start)", "Reversal", 0.55));
                    // Secondary: 61.8% retrace of diagonal
                    boolean bullish = wc.isBullish();
                    targets.add(target(
                        bullish ? w1End.getPrice() - diagLen * 0.618 : w1End.getPrice() + diagLen * 0.618,
                        "Ending diagonal 61.8% retrace (conservative reversal)", "Reversal", 0.3));
                    targets.add(target(
                        bullish ? w1End.getPrice() - diagLen * 1.618 : w1End.getPrice() + diagLen * 1.618,
                        "Ending diagonal 161.8% extension (extended reversal)", "Reversal", 0.15));
                }
            }
            case DOUBLE_ZIGZAG, COMPLEX_WXY -> {
                // WY is the final leg — treat like WC of a ZIGZAG
                if (wc.getCurrentWaveInProgress() == WaveLabel.WC && pivots.size() >= 3) {
                    EnrichedPivot wX_start = pivots.get(0);
                    EnrichedPivot wX_end   = pivots.get(1);
                    EnrichedPivot wY_start = pivots.get(2);
                    double xLength = Math.abs(wX_start.getPrice() - wX_end.getPrice());
                    boolean down = wX_end.getPrice() < wX_start.getPrice();
                    double dir = down ? -1 : 1;
                    targets.add(target(wY_start.getPrice() + dir * xLength * 1.0,   "WY = 100% of WX", "WY", 0.4));
                    targets.add(target(wY_start.getPrice() + dir * xLength * 1.618, "WY = 161.8% of WX", "WY", 0.35));
                    targets.add(target(wY_start.getPrice() + dir * xLength * 0.618, "WY = 61.8% of WX", "WY", 0.25));
                }
            }
        }
        return targets;
    }

    private WaveHypothesis.FibTarget target(double level, String ratio, String waveName, double conf) {
        return WaveHypothesis.FibTarget.builder()
                .level(level).ratio(ratio).waveName(waveName).confidence(conf).build();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INVALIDATION
    // ══════════════════════════════════════════════════════════════════════════

    private double computeInvalidation(WaveCount wc) {
        List<EnrichedPivot> pivots = wc.getPivots();
        if (pivots == null || pivots.size() < 2) return 0;

        return switch (wc.getWaveType()) {
            case IMPULSE -> {
                if (pivots.size() >= 3) {
                    // W4 invalidated beyond W1 territory, direction-aware.
                    yield wc.isBullish()
                            ? pivots.get(1).getPrice() * 0.99
                            : pivots.get(1).getPrice() * 1.01;
                }
                yield wc.isBullish()
                        ? pivots.get(0).getPrice() * 0.97
                        : pivots.get(0).getPrice() * 1.03;
            }
            case ZIGZAG, FLAT, EXPANDED_FLAT, RUNNING_FLAT -> {
                // Correction invalidated if price breaches the start of Wave A, direction-aware.
                yield wc.isBullish()
                        ? pivots.get(0).getPrice() * 0.98
                        : pivots.get(0).getPrice() * 1.02;
            }
            case TRIANGLE -> pivots.get(0).getPrice() * 0.97;
            case LEADING_DIAGONAL, ENDING_DIAGONAL -> {
                // Diagonal invalidated if price breaks back through W1 start (origin)
                yield wc.isBullish()
                    ? pivots.get(0).getPrice() * 0.98
                    : pivots.get(0).getPrice() * 1.02;
            }
            default -> pivots.get(0).getPrice() * 0.97;
        };
    }

    private String describeInvalidation(WaveCount wc, double level) {
        return switch (wc.getWaveType()) {
            case IMPULSE -> "Wave 4 cannot enter Wave 1 territory — invalidated "
                    + (wc.isBullish() ? "below " : "above ") + fmt(level);
            case ZIGZAG, FLAT, EXPANDED_FLAT ->
                    "Correction invalidated if price breaches Wave A origin at " + fmt(level);
            case TRIANGLE -> "Triangle invalidated on strong directional break below " + fmt(level);
            case LEADING_DIAGONAL, ENDING_DIAGONAL ->
                "Diagonal invalidated if price breaks through origin at " + fmt(level);
            default -> "Invalidated below " + fmt(level);
        };
    }

    private double computeScenarioInvalidation(WaveScenario.ScenarioDirection direction,
                                                List<WaveCount> counts) {
        if (counts.isEmpty()) return 0;
        return switch (direction) {
            case BULLISH_CONTINUATION, BULLISH_REVERSAL ->
                    counts.stream().mapToDouble(wc -> computeInvalidation(wc)).min().orElse(0);
            case BEARISH_CONTINUATION, BEARISH_REVERSAL ->
                    counts.stream().mapToDouble(wc -> computeInvalidation(wc)).max().orElse(0);
            case RANGE_RESOLUTION ->
                    counts.stream().mapToDouble(wc -> computeInvalidation(wc)).min().orElse(0);
        };
    }

    private String invalidationReason(WaveScenario.ScenarioDirection dir, double level) {
        return "Breach of " + fmt(level) + " eliminates all " + dir.name().toLowerCase() + " hypotheses";
    }

    // ══════════════════════════════════════════════════════════════════════════
    // EXPECTED PATTERNS
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * For a given wave position, returns the chart patterns commonly expected there.
     * This is the wave-position → pattern mapping from the architecture design.
     */
    private List<PatternType> expectedPatternsForWave(WaveLabel wave, WaveScenario.ScenarioDirection dir) {
        return switch (wave) {
            case W2 -> List.of(
                    PatternType.DOUBLE_BOTTOM,         // W2 often forms a double bottom
                    PatternType.FALLING_WEDGE,         // W2 zigzag looks like falling wedge
                    PatternType.INVERTED_HEAD_AND_SHOULDERS);

            case W4 -> List.of(
                    PatternType.SYMMETRICAL_TRIANGLE,  // W4 loves triangles
                    PatternType.ASCENDING_TRIANGLE,    // W4 in uptrend
                    PatternType.BULL_FLAG,             // W4 flag/pennant
                    PatternType.BULL_PENNANT,
                    PatternType.DOUBLE_BOTTOM,         // W4 double bottom = launch pad for W5
                    PatternType.DESCENDING_CHANNEL);   // W4 channel

            case W5 -> List.of(
                    PatternType.RISING_WEDGE,          // W5 diagonal
                    PatternType.DOUBLE_TOP,            // W5 exhaustion
                    PatternType.HEAD_AND_SHOULDERS);   // W5 reversal

            case WA -> List.of(
                    PatternType.DOUBLE_TOP,
                    PatternType.HEAD_AND_SHOULDERS,
                    PatternType.BEAR_FLAG);

            case WC -> List.of(
                    PatternType.DOUBLE_BOTTOM,         // WC capitulation → reversal
                    PatternType.INVERTED_HEAD_AND_SHOULDERS,
                    PatternType.FALLING_WEDGE,         // WC diagonal wedge
                    PatternType.TRIPLE_BOTTOM);

            case WE -> List.of(
                    PatternType.SYMMETRICAL_TRIANGLE,  // WE forms inside the overall triangle
                    PatternType.BULL_PENNANT,
                    PatternType.BEAR_PENNANT);

            default -> List.of();
        };
    }

    private List<PatternMatch> matchPatterns(List<PatternMatch> all, List<PatternType> expected) {
        return all.stream()
                .filter(p -> expected.contains(p.getType()))
                .toList();
    }

    private List<PatternMatch> findAlignedPatterns(WaveScenario.ScenarioDirection dir, List<PatternMatch> all) {
        boolean bullish = dir == WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                || dir == WaveScenario.ScenarioDirection.BULLISH_REVERSAL;
        return all.stream().filter(p -> bullish ? p.isBullish() : p.isBearish()).toList();
    }

    private List<PatternMatch> findConflictingPatterns(WaveScenario.ScenarioDirection dir, List<PatternMatch> all) {
        boolean bullish = dir == WaveScenario.ScenarioDirection.BULLISH_CONTINUATION
                || dir == WaveScenario.ScenarioDirection.BULLISH_REVERSAL;
        return all.stream().filter(p -> bullish ? p.isBearish() : p.isBullish()).toList();
    }

    // ══════════════════════════════════════════════════════════════════════════
    // DECISION POINTS
    // ══════════════════════════════════════════════════════════════════════════

    private List<WaveScenario.DecisionPoint> buildDecisionPoints(List<WaveHypothesis> hypotheses,
                                                                   WaveScenario.ScenarioDirection dir) {
        List<WaveScenario.DecisionPoint> points = new ArrayList<>();
        if (hypotheses.isEmpty()) return points;

        // Find the highest invalidation level across hypotheses
        double highestInvalidation = hypotheses.stream()
                .mapToDouble(WaveHypothesis::getInvalidationLevel).max().orElse(0);
        double lowestInvalidation = hypotheses.stream()
                .mapToDouble(WaveHypothesis::getInvalidationLevel).min().orElse(0);

        if (highestInvalidation != lowestInvalidation) {
            points.add(WaveScenario.DecisionPoint.builder()
                    .priceLevel(lowestInvalidation)
                    .label("Partial invalidation")
                    .ifBreachedBelow("Invalidates most conservative hypotheses — reduces scenario confidence")
                    .ifBreachedAbove("All hypotheses within scenario remain valid")
                    .build());
        }

        points.add(WaveScenario.DecisionPoint.builder()
                .priceLevel(highestInvalidation)
                .label("Full scenario invalidation")
                .ifBreachedBelow("ALL " + dir.name() + " hypotheses invalidated — reassess from scratch")
                .ifBreachedAbove("At least one hypothesis survives")
                .build());

        // If there's a primary target, that's also a decision point
        WaveHypothesis best = hypotheses.get(0);
        if (best.getPrimaryTarget() != null) {
            points.add(WaveScenario.DecisionPoint.builder()
                    .priceLevel(best.getPrimaryTarget().getLevel())
                    .label("Primary target zone")
                    .ifBreachedAbove("Consider partial profit / re-evaluate for next wave extension")
                    .ifBreachedBelow("Target not reached — monitor for pattern change")
                    .build());
        }

        return points;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // INDICATOR SUMMARIES
    // ══════════════════════════════════════════════════════════════════════════

    private Map<String, String> buildPivotIndicatorSummary(WaveCount wc) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (wc.getPivots() == null || wc.getPivots().isEmpty()) return summary;

        EnrichedPivot last = wc.getPivots().get(wc.getPivots().size() - 1);
        summary.put("RSI", fmt(last.getRsi()) + (last.getRsi() < 30 ? " — oversold" : last.getRsi() > 70 ? " — overbought" : " — neutral"));
        summary.put("EWO", fmt(last.getEwo()) + (wc.isEwoW3Peak() ? " — peaked at W3" : ""));
        summary.put("MACD_Histogram", fmt(last.getMacdHistogram()));
        summary.put("Bollinger_%B", fmt(last.getBollingerPctB()) + (last.isBollingerSqueeze() ? " — SQUEEZE" : ""));
        summary.put("ADX", fmt(last.getAdx()) + (last.getAdx() > 25 ? " — trending" : " — ranging"));
        summary.put("EMA_Stack", last.getEmaStackOrder());
        if (wc.isFinalWaveDivergence()) summary.put("Divergence", "MACD/EWO divergence detected at final wave — exhaustion signal");
        return summary;
    }

    private Map<String, String> buildIndicatorSummary(List<WaveCount> counts,
                                                        WaveScenario.ScenarioDirection dir) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (counts.isEmpty()) return summary;

        long bullishEma = counts.stream()
                .filter(wc -> !wc.getPivots().isEmpty()
                        && "BULLISH".equals(wc.getPivots().get(wc.getPivots().size() - 1).getEmaStackOrder()))
                .count();
        summary.put("EMA_Stack", bullishEma + "/" + counts.size() + " counts have bullish EMA stack");

        long divergenceCount = counts.stream().filter(WaveCount::isFinalWaveDivergence).count();
        if (divergenceCount > 0)
            summary.put("Divergence", divergenceCount + " counts show exhaustion divergence");

        return summary;
    }

    // ══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════════

    private Double computeDecisionLevel(WaveCount wc) {
        if (wc.getPivots() == null || wc.getPivots().size() < 2) return null;
        EnrichedPivot last = wc.getPivots().get(wc.getPivots().size() - 1);
        // The neckline of the last pivot or the prior swing high/low is a natural decision level
        return last.getBollingerMiddle() > 0 ? last.getBollingerMiddle() : null;
    }

    private String describeDecisionLevel(WaveCount wc, double level) {
        return "Bollinger mid-band at " + fmt(level) + " — reclaim confirms momentum; rejection extends correction";
    }

    private String directionLabel(WaveScenario.ScenarioDirection dir) {
        return switch (dir) {
            case BULLISH_CONTINUATION -> "Bullish continuation — temporary correction, higher highs ahead";
            case BEARISH_CONTINUATION -> "Bearish continuation — correction in bear trend, lower lows ahead";
            case BULLISH_REVERSAL -> "Bullish reversal — downtrend ending, new uptrend beginning";
            case BEARISH_REVERSAL -> "Bearish reversal — uptrend ending, correction or downtrend beginning";
            case RANGE_RESOLUTION -> "Range resolution — triangle/flat completing, explosive move either direction";
        };
    }

    private String fmt(double v) { return String.format("%.2f", v); }

    private int activationRank(String status) {
        if (status == null) return 3;
        return switch (status) {
            case "ACTIVE"   -> 0;
            case "WATCHING" -> 1;
            default         -> 2;
        };
    }
}
