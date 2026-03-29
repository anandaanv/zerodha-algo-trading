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
 *
 * Enhanced with:
 *  - RetracementAnalyzer: detects 50% bounce → "4C3 nested 1-2 accumulation" branch
 *  - WaveStructureClassifier: identifies prior decline as impulse (5) or zigzag (3)
 *    to refine narrative quality and suppress false double-bottom scenarios
 */
@Service
public class NestedCorrectiveContextBuilder {

    private final RetracementAnalyzer retracementAnalyzer = new RetracementAnalyzer();
    private final WaveStructureClassifier waveStructureClassifier = new WaveStructureClassifier();

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

            // Retrace analysis: check if current bounce looks like Wave B completed (45–65%)
            RetracementAnalyzer.RetracementContext retrace = retracementAnalyzer.analyze(pivots);
            WaveStructureClassifier.Structure priorStructure = waveStructureClassifier.classifyLastDecline(pivots);
            boolean isNestedAccumulation = retrace != null && retrace.isNestedAccumulationLikely();
            boolean priorDeclineImpulsive = priorStructure == WaveStructureClassifier.Structure.IMPULSE_5;

            // If retracement in 50–65% zone, reduce extension and truncation probs to add nested accumulation
            double nestedAccumProb = 0.0;
            if (isNestedAccumulation) {
                nestedAccumProb = 0.30;
                extensionProb  = clamp(extensionProb * 0.7, 0.15, 0.60);
            }
            double truncationProb = clamp(1.0 - extensionProb - nestedAccumProb, 0.10, 0.70);

            String parentWave = hintsW4 || ctx.getCurrentPosition() == WaveLabel.W4 || ctx.getCurrentPosition() == WaveLabel.WE
                    ? "W4"
                    : "Higher-degree correction";
            String dominantStructure = classifyDominantStructure(tfPatterns, ctx);

            // Build narrative with enhanced context
            StringBuilder narrativeSb = new StringBuilder();
            narrativeSb.append(tf).append(": nested corrective context favors ")
                    .append(parentWave).append(" -> B -> C-of-B decision zone. ")
                    .append("The current ").append(dominantStructure)
                    .append(" can either truncate early and start 4C immediately, ")
                    .append("or extend into the 0.618 C-of-B target before 4C.");
            if (isNestedAccumulation) {
                narrativeSb.append(" Current bounce of ~")
                        .append(String.format("%.0f", retrace.retracementPct * 100))
                        .append("% of prior decline suggests Wave B may be complete — ")
                        .append("watch for nested 1-2 accumulation as 4C Wave 1 starts.");
            }
            if (priorDeclineImpulsive) {
                narrativeSb.append(" Prior decline classified as 5-wave impulse — ")
                        .append("double-bottom scenario is less likely; corrective continuation into 4C is favored.");
            }
            String narrative = narrativeSb.toString();

            // Build branches list (mutable to conditionally add nested accumulation branch)
            List<NestedWaveContext.BranchHypothesis> branches = new ArrayList<>();
            branches.add(NestedWaveContext.BranchHypothesis.builder()
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
                    .build());
            branches.add(NestedWaveContext.BranchHypothesis.builder()
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
                    .build());
            if (isNestedAccumulation) {
                // 4C3 nested 1-2 accumulation: Wave B appears complete at ~50–65% retrace
                // Price may start a leading diagonal / nested 1-2 runup (4C sub-wave 1-2)
                double accumulationTarget = retrace != null
                        ? retrace.declineStart * 0.90  // initial 4C1 target ≈ 90% of prior decline origin
                        : extensionTarget;
                branches.add(NestedWaveContext.BranchHypothesis.builder()
                        .code("4C3_NESTED_ACCUMULATION")
                        .label("4C3 start — nested 1-2 accumulation (leading diagonal possible)")
                        .probability(nestedAccumProb)
                        .targetLevel(accumulationTarget)
                        .trigger("Bounce holds above Wave B low (" + fmt(last.getPrice())
                                + ") and begins a series of higher lows (nested 1-2 structure)")
                        .rationale("Retrace of ~" + String.format("%.0f", retrace != null ? retrace.retracementPct * 100 : 50)
                                + "% of prior decline places us at typical Wave B completion. "
                                + "A nested 1-2 / leading diagonal at the start of 4C would produce "
                                + "a slow grind higher before an impulsive Wave 3 of 4C launches."
                                + (priorDeclineImpulsive
                                    ? " Prior decline was 5-wave impulsive — this supports a complex 4C structure."
                                    : ""))
                        .nextHigherDegreeMove("4C impulse — Wave 1-2 accumulation before 4C Wave 3 extension")
                        .build());
            }

            results.add(NestedWaveContext.builder()
                    .timeframe(tf)
                    .higherDegreeWave(parentWave)
                    .correctiveLeg("B")
                    .activeSubwave("C-of-B")
                    .dominantStructure(dominantStructure)
                    .narrative(narrative)
                    .anchorLevel(last.getPrice())
                    .invalidationLevel(resolveInvalidation(ctx, last))
                    .branches(branches)
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
