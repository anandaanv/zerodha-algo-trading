package com.dtech.ta.elliott;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Builds an explicit nested corrective narrative from the flatter Elliott engine state.
 *
 * The current engine already knows enough to say "triangle in W4 or B-wave position"
 * and "terminal risk is present". This builder packages that into the higher-degree
 * wording traders expect, e.g. W4 -> B -> C-of-B with live alternate branches.
 */
@Service
public class NestedCorrectiveContextBuilder {

    public List<NestedWaveContext> build(
            List<String> tfOrder,
            String primaryTf,
            Map<String, TfContext> tfContexts,
            Map<String, List<EnrichedPivot>> enrichedByTf,
            List<PatternMatch> patterns) {

        List<NestedWaveContext> results = new ArrayList<>();

        for (String tf : tfOrder) {
            if (tf.equals(primaryTf)) continue;

            TfContext ctx = tfContexts.get(tf);
            List<EnrichedPivot> pivots = enrichedByTf.get(tf);
            if (ctx == null || pivots == null || pivots.size() < 2) continue;

            List<PatternMatch> tfPatterns = patterns.stream()
                    .filter(pm -> tf.equals(pm.getTimeframe()))
                    .toList();
            if (tfPatterns.isEmpty()) continue;

            boolean hasTriangle = tfPatterns.stream().anyMatch(this::isTrianglePattern);
            boolean hintsW4 = tfPatterns.stream().anyMatch(pm -> hasHint(pm, WaveLabel.W4));
            boolean hintsB = tfPatterns.stream().anyMatch(pm -> hasHint(pm, WaveLabel.WB));
            boolean terminalRisk = tfPatterns.stream().anyMatch(this::isTerminalRiskPattern);

            if (!hasTriangle || (!hintsW4 && !hintsB && ctx.getCurrentPosition() != WaveLabel.WE)) {
                continue;
            }

            EnrichedPivot last = pivots.get(pivots.size() - 1);
            EnrichedPivot prev = pivots.get(pivots.size() - 2);
            boolean extensionUp = last.getPrice() <= prev.getPrice();
            double extensionTarget = extensionUp
                    ? last.getPrice() + Math.abs(prev.getPrice() - last.getPrice()) * 0.618
                    : last.getPrice() - Math.abs(prev.getPrice() - last.getPrice()) * 0.618;

            double extensionProb = 0.55;
            if (hintsW4 && hintsB) extensionProb += 0.10;
            if (ctx.getCurrentPosition() == WaveLabel.WE) extensionProb += 0.10;
            if (terminalRisk) extensionProb -= 0.15;
            extensionProb = clamp(extensionProb, 0.25, 0.75);
            double truncationProb = 1.0 - extensionProb;

            String parentWave = hintsW4 || ctx.getCurrentPosition() == WaveLabel.W4 || ctx.getCurrentPosition() == WaveLabel.WE
                    ? "W4"
                    : "Higher-degree correction";
            String dominantStructure = classifyDominantStructure(tfPatterns, ctx);
            String narrative = tf + ": nested corrective context favors "
                    + parentWave + " -> B -> C-of-B decision zone. "
                    + "The current " + dominantStructure
                    + " can either truncate early and start 4C immediately, "
                    + "or extend into the 0.618 C-of-B target before 4C.";

            results.add(NestedWaveContext.builder()
                    .timeframe(tf)
                    .higherDegreeWave(parentWave)
                    .correctiveLeg("B")
                    .activeSubwave("C-of-B")
                    .dominantStructure(dominantStructure)
                    .narrative(narrative)
                    .anchorLevel(last.getPrice())
                    .invalidationLevel(resolveInvalidation(ctx, last))
                    .branches(List.of(
                            NestedWaveContext.BranchHypothesis.builder()
                                    .code("TRUNCATED_C_OF_B")
                                    .label("Truncated C-of-B -> early 4C")
                                    .probability(truncationProb)
                                    .targetLevel(last.getPrice())
                                    .trigger(extensionUp
                                            ? "Lose the latest swing support near " + fmt(last.getPrice())
                                            : "Reject back below the latest swing near " + fmt(last.getPrice()))
                                    .rationale(terminalRisk
                                            ? "Terminal-risk patterns on the same timeframe argue for early exhaustion."
                                            : "Failure to complete the triangle thrust would imply the C-of-B truncated.")
                                    .nextHigherDegreeMove("4C")
                                    .build(),
                            NestedWaveContext.BranchHypothesis.builder()
                                    .code("EXTENDED_C_OF_B_TO_0.618")
                                    .label("Extended C-of-B -> 0.618 target before 4C")
                                    .probability(extensionProb)
                                    .targetLevel(extensionTarget)
                                    .trigger(extensionUp
                                            ? "Hold above " + fmt(last.getPrice()) + " and expand toward " + fmt(extensionTarget)
                                            : "Stay below " + fmt(last.getPrice()) + " and continue toward " + fmt(extensionTarget))
                                    .rationale(hintsW4 && hintsB
                                            ? "The same higher-timeframe triangle carries both W4 and B-wave signatures, so one more C-of-B thrust remains live."
                                            : "Corrective continuation remains open into the 0.618 retracement target.")
                                    .nextHigherDegreeMove("4C")
                                    .build()))
                    .build());
        }

        results.sort(Comparator.comparing(NestedWaveContext::getTimeframe));
        return results;
    }

    private boolean isTrianglePattern(PatternMatch pm) {
        return pm.getType() == PatternType.SYMMETRICAL_TRIANGLE
                || pm.getType() == PatternType.ASCENDING_TRIANGLE
                || pm.getType() == PatternType.DESCENDING_TRIANGLE;
    }

    private boolean hasHint(PatternMatch pm, WaveLabel label) {
        return pm.getWaveContextHints() != null
                && pm.getWaveContextHints().stream().anyMatch(h -> h.getImpliedCurrentPosition() == label);
    }

    private boolean isTerminalRiskPattern(PatternMatch pm) {
        return pm.getType() == PatternType.RISING_WEDGE
                || pm.getType() == PatternType.DOUBLE_TOP
                || pm.getType() == PatternType.HEAD_AND_SHOULDERS;
    }

    private String classifyDominantStructure(List<PatternMatch> patterns, TfContext ctx) {
        if (patterns.stream().anyMatch(this::isTrianglePattern)) return "triangle / terminal thrust zone";
        if (ctx.getStructureType() != null) return ctx.getStructureType().name();
        return "corrective compression";
    }

    private double resolveInvalidation(TfContext ctx, EnrichedPivot last) {
        if (ctx.getW4HardFloor() != null) return ctx.getW4HardFloor();
        if (ctx.getCorrectionOrigin() != null) return ctx.getCorrectionOrigin();
        return last.getPrice();
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String fmt(double value) {
        return String.format("%.2f", value);
    }
}
