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
            List<PatternMatch> patterns,
            double currentPrice,
            List<WaveCount> waveCounts) {

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
                    .targetLevel(extensionUp
                            ? last.getPrice() - (extensionTarget - last.getPrice())
                            : last.getPrice() + (last.getPrice() - extensionTarget))
                    .bullish(!extensionUp)
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
                    .bullish(extensionUp)
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

            // Adjust branch probabilities based on current price vs anchor
            double anchorPrice = last.getPrice();
            adjustBranchProbabilities(branches, anchorPrice, currentPrice);
            // Update narrative if truncation has triggered
            boolean truncationTriggered = currentPrice < anchorPrice && !extensionUp
                    || currentPrice > anchorPrice && extensionUp;
            if (truncationTriggered) {
                narrative = tf + ": Truncation confirmed — price has moved through anchor "
                        + fmt(anchorPrice) + ". Wave 4C is likely in progress. "
                        + "Extended scenario probability reduced significantly.";
            }

            results.add(NestedWaveContext.builder()
                    .timeframe(tf)
                    .higherDegreeWave(parentWave)
                    .correctiveLeg("B")
                    .activeSubwave("C-of-B")
                    .dominantStructure(dominantStructure)
                    .narrative(narrative)
                    .anchorLevel(last.getPrice())
                    .anchorTimestamp(last.getTimestamp())
                    .invalidationLevel(resolveInvalidation(ctx, last))
                    .branches(branches)
                    .build());
        }

        enrichTriggeredBranchSubwaves(results, enrichedByTf, tfOrder, waveCounts);
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

    /**
     * Adjusts TRUNCATED vs EXTENDED branch probabilities based on current price vs anchor.
     * If price has already broken through the anchor (truncation trigger met), boost TRUNCATED.
     * Also sets triggered/triggerPrice/currentPrice fields on each branch.
     */
    private void adjustBranchProbabilities(List<NestedWaveContext.BranchHypothesis> branches,
                                             double anchorPrice, double currentPrice) {
        if (currentPrice <= 0 || anchorPrice <= 0) return;

        // Determine if truncation trigger has been hit (price below anchor for downward corrections)
        boolean truncationTriggered = currentPrice < anchorPrice;
        double distFromAnchor = (currentPrice - anchorPrice) / anchorPrice; // negative if below

        for (int i = 0; i < branches.size(); i++) {
            NestedWaveContext.BranchHypothesis b = branches.get(i);
            // Mark trigger/price fields
            branches.set(i, NestedWaveContext.BranchHypothesis.builder()
                    .code(b.getCode())
                    .label(b.getLabel())
                    .targetLevel(b.getTargetLevel())
                    .trigger(b.getTrigger())
                    .rationale(b.getRationale())
                    .nextHigherDegreeMove(b.getNextHigherDegreeMove())
                    .bullish(b.getBullish())
                    .triggerPrice(anchorPrice)
                    .currentPrice(currentPrice)
                    .triggered(b.getCode() != null && b.getCode().startsWith("TRUNCATED") && truncationTriggered)
                    .probability(computeAdjustedProbability(b.getCode(), truncationTriggered, distFromAnchor, b.getProbability()))
                    .build());
        }
    }

    private double computeAdjustedProbability(String code, boolean truncationTriggered,
                                               double distFromAnchor, double originalProb) {
        if (code == null) return originalProb;
        boolean isTruncated = code.startsWith("TRUNCATED");
        boolean isExtended  = code.startsWith("EXTENDED");

        if (truncationTriggered) {
            // Price is below anchor — truncation has triggered
            if (isTruncated) return 0.85;
            if (isExtended)  return 0.15;
        } else {
            // Price is above anchor — still in decision zone
            double absDist = Math.abs(distFromAnchor);
            if (absDist <= 0.01) {
                // Within 1% of anchor — neutral
                if (isTruncated) return 0.50;
                if (isExtended)  return 0.50;
            } else if (absDist <= 0.03) {
                // 1-3% above anchor — extension favored
                if (isTruncated) return 0.40;
                if (isExtended)  return 0.60;
            } else {
                // >3% above anchor — extension more likely
                if (isTruncated) return 0.30;
                if (isExtended)  return 0.70;
            }
        }
        return originalProb; // non-TRUNCATED/EXTENDED branches keep original
    }

    /**
     * When TRUNCATED_C_OF_B has triggered, count sub-waves from the anchor timestamp
     * and classify the emerging 4C structure (diagonal vs clean channel impulse).
     */
    private void enrichTriggeredBranchSubwaves(List<NestedWaveContext> contexts,
                                                Map<String, List<EnrichedPivot>> enrichedByTf,
                                                List<String> tfOrder,
                                                List<WaveCount> waveCounts) {
        for (NestedWaveContext ctx : contexts) {
            java.time.Instant anchorTs = ctx.getAnchorTimestamp();
            if (anchorTs == null) continue;

            // Step 1: try same-TF pivots after the anchor timestamp
            List<EnrichedPivot> subPivots = new java.util.ArrayList<>();
            List<EnrichedPivot> sameTfPivots = enrichedByTf.get(ctx.getTimeframe());
            if (sameTfPivots != null) {
                for (EnrichedPivot p : sameTfPivots) {
                    if (p.getTimestamp() != null && p.getTimestamp().isAfter(anchorTs)) {
                        subPivots.add(p);
                    }
                }
            }

            // Step 2: if same-TF has no sub-pivots, fall back to next lower TF
            if (subPivots.isEmpty()) {
                int tfIdx = tfOrder.indexOf(ctx.getTimeframe());
                if (tfIdx >= 0 && tfIdx + 1 < tfOrder.size()) {
                    String lowerTf = tfOrder.get(tfIdx + 1);
                    List<EnrichedPivot> lowerPivots = enrichedByTf.get(lowerTf);
                    if (lowerPivots != null) {
                        for (EnrichedPivot p : lowerPivots) {
                            if (p.getTimestamp() != null && p.getTimestamp().isAfter(anchorTs)) {
                                subPivots.add(p);
                            }
                        }
                    }
                }
            }

            // Now classify the 4C sub-wave stage from the sub-pivot count
            List<NestedWaveContext.BranchHypothesis> updated = new java.util.ArrayList<>();
            for (NestedWaveContext.BranchHypothesis b : ctx.getBranches()) {
                if (!Boolean.TRUE.equals(b.isTriggered()) || b.getCode() == null
                        || !b.getCode().startsWith("TRUNCATED")) {
                    updated.add(b);
                    continue;
                }

                int n = subPivots.size();
                String subwaveLabel;
                String subwaveStructureType = null;
                String subwaveNextMove;
                Double subwaveInvalidation = null;

                if (n == 0) {
                    subwaveLabel    = "4C not yet started";
                    subwaveNextMove = "Awaiting first sub-swing below anchor " + fmt(ctx.getAnchorLevel());
                } else if (n == 1) {
                    EnrichedPivot p0 = subPivots.get(0);
                    subwaveLabel    = "4C1 in progress";
                    subwaveInvalidation = p0.getPrice();
                    subwaveNextMove = "4C1 in progress — watch for bounce to confirm 4C2";

                    // Check if an ENDING_DIAGONAL on the same timeframe matches this sub-wave
                    if (waveCounts != null) {
                        double subAtr = p0.getAtrAtPivot() > 0 ? p0.getAtrAtPivot() : Math.abs(ctx.getAnchorLevel() - p0.getPrice()) * 0.05;
                        WaveCount matchingDiag = waveCounts.stream()
                            .filter(wc -> WaveCount.WaveType.ENDING_DIAGONAL.equals(wc.getWaveType()))
                            .filter(wc -> ctx.getTimeframe().equals(wc.getPrimaryTimeframe())
                                       || (wc.getPrimaryTimeframe() != null && ctx.getTimeframe() != null
                                           && (ctx.getTimeframe().equals(wc.getPrimaryTimeframe())
                                               || tfOrder.indexOf(ctx.getTimeframe()) + 1 == tfOrder.indexOf(wc.getPrimaryTimeframe()))))
                            .filter(wc -> wc.getPivots() != null && !wc.getPivots().isEmpty())
                            .filter(wc -> Math.abs(wc.getPivots().get(wc.getPivots().size() - 1).getPrice() - p0.getPrice()) <= subAtr * 2)
                            .findFirst().orElse(null);
                        if (matchingDiag != null) {
                            double diagOrigin = matchingDiag.getPivots().get(0).getPrice();
                            subwaveStructureType = "ENDING_DIAGONAL";
                            subwaveNextMove = String.format("Ending diagonal complete — expect sharp reversal to %.2f", diagOrigin);
                            subwaveInvalidation = diagOrigin;
                        }
                    }
                } else if (n == 2) {
                    EnrichedPivot p0 = subPivots.get(0);
                    EnrichedPivot p1 = subPivots.get(1);
                    subwaveLabel    = "4C2 retrace in progress";
                    subwaveInvalidation = p0.getPrice(); // 4C1 extreme = sub-count invalidation
                    double retrace = p0.getPrice() != 0
                            ? Math.abs(p1.getPrice() - p0.getPrice()) / Math.abs(ctx.getAnchorLevel() - p0.getPrice())
                            : 0;
                    subwaveNextMove = String.format("4C2 bounce in progress (%.0f%% retrace) — watch for 4C3 continuation",
                            retrace * 100);
                } else {
                    // Diagonal: 4C2 bounce retraces >78.6% of 4C1 (W2 overlaps W1 territory)
                    EnrichedPivot p0 = subPivots.get(0); // 4C1 start (high for bearish)
                    EnrichedPivot p1 = subPivots.get(1); // 4C1 end (low for bearish)
                    EnrichedPivot p2 = subPivots.get(2); // 4C2 end (bounce high)
                    double c1Range = Math.abs(p1.getPrice() - p0.getPrice());
                    double c2Retrace = c1Range > 0
                            ? Math.abs(p2.getPrice() - p1.getPrice()) / c1Range : 0;
                    boolean diagonal = c2Retrace > 0.786;
                    subwaveLabel        = "4C" + Math.min(n, 5) + " in progress";
                    subwaveStructureType = diagonal ? "DIAGONAL" : "CHANNEL_IMPULSE";
                    subwaveInvalidation  = p1.getPrice(); // current 4C wave extreme
                    subwaveNextMove = diagonal
                            ? "Leading diagonal developing — deep W2 retrace; expect W3-of-4C acceleration after 4C2 completes"
                            : "Channel impulse — W3-of-4C should extend strongly below " + fmt(p1.getPrice());
                }

                updated.add(NestedWaveContext.BranchHypothesis.builder()
                        .code(b.getCode())
                        .label(b.getLabel())
                        .probability(b.getProbability())
                        .targetLevel(b.getTargetLevel())
                        .trigger(b.getTrigger())
                        .rationale(b.getRationale())
                        .nextHigherDegreeMove(b.getNextHigherDegreeMove())
                        .bullish(b.getBullish())
                        .triggered(b.isTriggered())
                        .triggerPrice(b.getTriggerPrice())
                        .currentPrice(b.getCurrentPrice())
                        .subwaveLabel(subwaveLabel)
                        .subwaveStructureType(subwaveStructureType)
                        .subwaveNextMove(subwaveNextMove)
                        .subwaveInvalidation(subwaveInvalidation)
                        .build());
            }
            ctx.setBranches(updated);
        }
    }
}
