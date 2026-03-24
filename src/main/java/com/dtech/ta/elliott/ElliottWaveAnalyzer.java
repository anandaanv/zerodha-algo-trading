package com.dtech.ta.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ElliottWaveAnalyzer {

    private final PivotIndicatorEnricher enricher;
    private final PivotPatternDetector patternDetector;
    private final WaveCounter waveCounter;
    private final ScenarioBuilder scenarioBuilder;
    private final GapDetectorService gapDetector;
    private final NestedCorrectiveContextBuilder nestedCorrectiveContextBuilder;

    public ElliottWaveAnalysis analyze(
            Map<String, List<ZigZagPoint>> pivotsByTf,
            Map<String, MarketStructureData> structureByTf,
            Map<String, BarSeries> seriesByTf,
            List<String> tfOrder,
            String primaryTf) {

        // Layer 4: Enrich pivots
        Map<String, List<EnrichedPivot>> enrichedByTf = new LinkedHashMap<>();
        for (String tf : tfOrder) {
            List<ZigZagPoint> raw = pivotsByTf.get(tf);
            MarketStructureData structure = structureByTf.get(tf);
            BarSeries series = seriesByTf.get(tf);
            if (raw == null || structure == null || series == null) continue;
            enrichedByTf.put(tf, enricher.enrich(raw, structure, series));
        }

        // Gap detection
        Map<String, List<GapLevel>> gapsByTf = new LinkedHashMap<>();
        for (String tf : tfOrder) {
            BarSeries series = seriesByTf.get(tf);
            if (series != null) gapsByTf.put(tf, gapDetector.detectGaps(series, tf));
        }
        List<GapLevel> significantGaps = gapsByTf.values().stream()
            .flatMap(List::stream)
            .filter(g -> g.getSignificance() != GapLevel.GapSignificance.MINOR)
            .sorted(Comparator.comparingInt((GapLevel g) -> -g.getSignificance().ordinal()))
            .collect(Collectors.toList());

        // Layer 5: Detect patterns
        List<PatternMatch> patterns = new ArrayList<>();
        for (String tf : tfOrder) {
            List<EnrichedPivot> tfPivots = enrichedByTf.get(tf);
            if (tfPivots != null && !tfPivots.isEmpty())
                patterns.addAll(patternDetector.detect(tfPivots, tf));
        }

        // Layer 6: Wave counts
        List<WaveCount> waveCounts = waveCounter.generateCounts(enrichedByTf, tfOrder);

        // Layer 7: Scenarios
        List<WaveScenario> scenarios = scenarioBuilder.build(waveCounts, patterns, primaryTf);

        // TfContext: top-down extraction
        Map<String, TfContext> tfContexts = buildTfContexts(tfOrder, waveCounts);

        // Bottom-up: child TF confirmations boost parent scores
        applyBottomUpBoosts(tfContexts, tfOrder, waveCounts);

        // Cross-TF narrative
        String narrative = buildCrossTfNarrative(tfOrder, tfContexts, gapsByTf, patterns);
        String nestedBranchNarrative = buildNestedBranchNarrative(tfOrder, tfContexts, patterns, scenarios);
        List<NestedWaveContext> nestedCorrectiveContexts = nestedCorrectiveContextBuilder.build(
                tfOrder, primaryTf, tfContexts, enrichedByTf, patterns);

        return ElliottWaveAnalysis.builder()
            .scenarios(scenarios).allPatterns(patterns)
            .enrichedPivots(enrichedByTf).waveCounts(waveCounts)
            .primaryTimeframe(primaryTf).timeframes(tfOrder)
            .gapsByTf(gapsByTf).significantGaps(significantGaps)
            .tfContexts(tfContexts).crossTfNarrative(narrative)
            .nestedBranchNarrative(nestedBranchNarrative)
            .nestedCorrectiveContexts(nestedCorrectiveContexts)
            .build();
    }

    private Map<String, TfContext> buildTfContexts(List<String> tfOrder, List<WaveCount> allCounts) {
        Map<String, TfContext> contexts = new LinkedHashMap<>();
        for (String tf : tfOrder) {
            WaveCount best = allCounts.stream()
                .filter(wc -> tf.equals(wc.getPrimaryTimeframe()))
                .max(Comparator.comparingInt(WaveCount::totalScore))
                .orElse(null);
            if (best == null) continue;

            TfContext.TfContextBuilder ctx = TfContext.builder()
                .timeframe(tf)
                .currentPosition(best.getCurrentWaveInProgress())
                .structureType(best.getWaveType())
                .bullishContext(isBullishContext(best))
                .drivingCount(best)
                .narrative(buildTfNarrative(tf, best));

            if (best.getCurrentWaveInProgress() == WaveLabel.W4
                    && best.getPivots() != null && best.getPivots().size() >= 4) {
                EnrichedPivot w1e = best.getPivots().get(1);
                EnrichedPivot w2e = best.getPivots().get(2);
                EnrichedPivot w3e = best.getPivots().get(3);
                double w3len = Math.abs(w3e.getPrice() - w2e.getPrice());
                ctx.w4SupportMin(w3e.getPrice() - w3len * 0.382)
                   .w4SupportMax(w3e.getPrice() - w3len * 0.618)
                   .w4HardFloor(w1e.getPrice());
            }
            if ((best.getWaveType() == WaveCount.WaveType.ZIGZAG
                    || best.getWaveType() == WaveCount.WaveType.FLAT
                    || best.getWaveType() == WaveCount.WaveType.EXPANDED_FLAT)
                    && best.getPivots() != null && !best.getPivots().isEmpty()) {
                ctx.correctionOrigin(best.getPivots().get(0).getPrice());
            }
            contexts.put(tf, ctx.build());
        }
        return contexts;
    }

    private boolean isBullishContext(WaveCount wc) {
        if (wc.getWaveType() == WaveCount.WaveType.IMPULSE) {
            WaveLabel pos = wc.getCurrentWaveInProgress();
            return pos == WaveLabel.W1 || pos == WaveLabel.W2
                || pos == WaveLabel.W3 || pos == WaveLabel.W4 || pos == WaveLabel.W5;
        }
        return false;
    }

    private String buildTfNarrative(String tf, WaveCount best) {
        if (best.getPivots() == null || best.getPivots().isEmpty()) return tf + ": insufficient data";
        EnrichedPivot last = best.getPivots().get(best.getPivots().size() - 1);
        return switch (best.getCurrentWaveInProgress()) {
            case W4 -> {
                EnrichedPivot w3e = best.getPivots().size() >= 4 ? best.getPivots().get(3) : last;
                yield tf + ": W4 correction in progress after W3 peak at " + fmt(w3e.getPrice())
                    + ". W5 expected after W4 completes.";
            }
            case W5      -> tf + ": W5 final impulse in progress. Watch for exhaustion/divergence.";
            case WA      -> tf + ": Wave A correction underway. B-wave bounce expected after A completes.";
            case WB      -> tf + ": Wave B counter-rally. C-wave decline follows.";
            case WC      -> tf + ": Wave C final decline in progress. Full correction completes at C.";
            case UNKNOWN -> tf + ": Impulse complete at " + fmt(last.getPrice()) + ". ABC correction expected.";
            default      -> tf + ": In " + best.getCurrentWaveInProgress() + " at " + fmt(last.getPrice());
        };
    }

    private void applyBottomUpBoosts(Map<String, TfContext> ctxs, List<String> tfOrder, List<WaveCount> counts) {
        for (int i = 0; i < tfOrder.size() - 1; i++) {
            TfContext parent = ctxs.get(tfOrder.get(i));
            TfContext child  = ctxs.get(tfOrder.get(i + 1));
            if (parent == null || child == null) continue;

            if (parent.getCurrentPosition() == WaveLabel.W4
                    && (child.getStructureType() == WaveCount.WaveType.ZIGZAG
                     || child.getStructureType() == WaveCount.WaveType.FLAT
                     || child.getStructureType() == WaveCount.WaveType.TRIANGLE)) {
                parent.setChildConfirmationBonus(parent.getChildConfirmationBonus() + 15);
                parent.getChildConfirmations().add(child.getTimeframe() + " "
                    + child.getStructureType() + " confirms " + parent.getTimeframe() + " W4");
                if (parent.getDrivingCount() != null)
                    parent.getDrivingCount().setCrossTfScore(parent.getDrivingCount().getCrossTfScore() + 15);
            }
            if (parent.getCurrentPosition() == WaveLabel.WA
                    && child.getStructureType() == WaveCount.WaveType.IMPULSE
                    && child.getCurrentPosition() == WaveLabel.W5) {
                parent.setChildConfirmationBonus(parent.getChildConfirmationBonus() + 12);
                parent.getChildConfirmations().add(child.getTimeframe()
                    + " 5-wave impulse down confirms " + parent.getTimeframe() + " Wave A is impulsive");
            }
        }
    }

    private String buildCrossTfNarrative(List<String> tfOrder, Map<String, TfContext> ctxs,
                                          Map<String, List<GapLevel>> gapsByTf,
                                          List<PatternMatch> patterns) {
        StringBuilder sb = new StringBuilder("=== CROSS-TIMEFRAME CONTEXT ===\n\n");

        for (String tf : tfOrder) {
            TfContext ctx = ctxs.get(tf);
            if (ctx == null) continue;
            sb.append(ctx.getNarrative()).append("\n");
            ctx.getChildConfirmations().forEach(c -> sb.append("  ✓ ").append(c).append("\n"));
            if (ctx.getW4SupportMin() != null)
                sb.append("  W4 support zone: ").append(fmt(ctx.getW4SupportMin()))
                  .append("–").append(fmt(ctx.getW4SupportMax()))
                  .append(" | Hard floor: ").append(fmt(ctx.getW4HardFloor())).append("\n");
        }

        sb.append("\nPATTERN → WAVE CONTEXT:\n");
        boolean anyHints = false;
        for (PatternMatch pm : patterns) {
            if (pm.getWaveContextHints() == null || pm.getWaveContextHints().isEmpty()) continue;
            anyHints = true;
            sb.append("  [").append(pm.getTimeframe()).append("] ").append(pm.getType())
              .append("[").append(pm.getStatus()).append("] conf=").append(fmt(pm.getConfidence())).append("\n");
            pm.getWaveContextHints().forEach(h ->
                sb.append("    → ").append(h.getImpliedCurrentPosition())
                  .append(" next:").append(h.getImpliedNextWave())
                  .append(" prob=").append(fmt(h.getProbability())).append(" | ")
                  .append(h.getNarrative()).append("\n"));
        }
        if (!anyHints) sb.append("  (none)\n");

        sb.append("\nGAP CONFLUENCE:\n");
        boolean anyGap = false;
        for (Map.Entry<String, List<GapLevel>> e : gapsByTf.entrySet()) {
            for (GapLevel g : e.getValue()) {
                if (g.getSignificance() == GapLevel.GapSignificance.MINOR) continue;
                anyGap = true;
                sb.append("  [").append(g.getTimeframe()).append("] ").append(g.getDirection())
                  .append(" ").append(fmt(g.getGapBottom())).append("–").append(fmt(g.getGapTop()))
                  .append(" ").append(g.getSignificance()).append(" ").append(g.getFillStatus())
                  .append(g.isNearCurrentPrice() ? " ★NEAR" : "").append("\n");
            }
        }
        if (!anyGap) sb.append("  (none)\n");

        return sb.toString();
    }

    private String buildNestedBranchNarrative(List<String> tfOrder,
                                              Map<String, TfContext> ctxs,
                                              List<PatternMatch> patterns,
                                              List<WaveScenario> scenarios) {
        StringBuilder sb = new StringBuilder("NESTED CORRECTIVE BRANCHES:\n");
        boolean any = false;

        for (String tf : tfOrder) {
            TfContext ctx = ctxs.get(tf);
            if (ctx == null) continue;

            BranchSnapshot snapshot = deriveBranchSnapshot(tf, ctx, patterns, scenarios);
            if (snapshot == null) continue;

            any = true;
            sb.append("  [").append(tf).append("] ").append(snapshot.contextPath).append("\n");
            sb.append("    Current: ").append(snapshot.currentState).append("\n");
            if (snapshot.primaryThesis != null) {
                sb.append("    Thesis: ").append(snapshot.primaryThesis).append("\n");
            }
            for (BranchOption option : snapshot.options) {
                sb.append("    - ").append(option.label);
                if (option.confidence != null) {
                    sb.append(" (conf=").append(fmt(option.confidence)).append(")");
                }
                if (option.target != null) {
                    sb.append(" target=").append(fmt(option.target));
                }
                sb.append(": ").append(option.description).append("\n");
            }
        }

        if (!any) {
            sb.append("  (none)\n");
        }

        return sb.toString();
    }

    private BranchSnapshot deriveBranchSnapshot(String tf, TfContext ctx,
                                                List<PatternMatch> patterns,
                                                List<WaveScenario> scenarios) {
        WaveLabel position = ctx.getCurrentPosition();
        if (position == null || position == WaveLabel.UNKNOWN) return null;

        boolean hasTriangle = patterns.stream()
                .filter(p -> tf.equals(p.getTimeframe()))
                .anyMatch(p -> p.getType() == PatternType.SYMMETRICAL_TRIANGLE
                        || p.getType() == PatternType.ASCENDING_TRIANGLE
                        || p.getType() == PatternType.DESCENDING_TRIANGLE);
        boolean hasWedge = patterns.stream()
                .filter(p -> tf.equals(p.getTimeframe()))
                .anyMatch(p -> p.getType() == PatternType.RISING_WEDGE
                        || p.getType() == PatternType.FALLING_WEDGE);

        String thesis = null;
        String contextPath = null;
        List<BranchOption> options = new ArrayList<>();

        if (position == WaveLabel.W4 || position == WaveLabel.WB || position == WaveLabel.WE) {
            thesis = position == WaveLabel.W4
                    ? "Higher-degree correction can still resolve into a terminal thrust"
                    : position == WaveLabel.WB
                    ? "Wave B is still the deceptive counter-trend phase"
                    : "Triangle completion is approaching the final thrust";

            contextPath = switch (position) {
                case W4 -> "W4 -> nested B/C branch";
                case WB -> "W4 -> B -> C of B";
                case WE -> "B / triangle terminal leg -> final thrust";
                default -> "correction";
            };

            options.add(BranchOption.builder()
                    .label("Branch A: truncated terminal leg")
                    .confidence(hasWedge ? 0.58 : 0.52)
                    .description("Failure before the projected extension completes can trigger the next decline immediately.")
                    .build());
            options.add(BranchOption.builder()
                    .label("Branch B: extension toward the 0.618 target")
                    .confidence(hasTriangle ? 0.66 : 0.60)
                    .target(computeFibonacciBranchTarget(ctx))
                    .description("If the move extends, it can complete the final corrective leg before the next reversal.")
                    .build());
        } else if (position == WaveLabel.WC) {
            thesis = "Final corrective leg is in play; reversal risk rises on completion";
            contextPath = "W4 -> B -> C of B";
            options.add(BranchOption.builder()
                    .label("Branch A: truncated C")
                    .confidence(0.57)
                    .description("C may fail early and roll over into the next decline before a full target is reached.")
                    .build());
            options.add(BranchOption.builder()
                    .label("Branch B: full C extension")
                    .confidence(0.63)
                    .target(computeFibonacciBranchTarget(ctx))
                    .description("C can still extend toward the measured 0.618 area before the next leg begins.")
                    .build());
        } else if (position == WaveLabel.W2) {
            thesis = "Early retracement is still unfolding; it may be a precursor to a larger corrective branch";
            contextPath = "W2 / retracement";
            options.add(BranchOption.builder()
                    .label("Branch A: shallow truncation")
                    .confidence(0.51)
                    .description("Retracement may fail early and preserve the prior trend.")
                    .build());
            options.add(BranchOption.builder()
                    .label("Branch B: deeper continuation")
                    .confidence(0.55)
                    .description("Retracement may still extend before the next impulsive leg appears.")
                    .build());
        } else {
            return null;
        }

        return BranchSnapshot.builder()
                .contextPath(contextPath)
                .currentState(ctx.getNarrative())
                .primaryThesis(thesis)
                .options(options)
                .build();
    }

    private Double computeFibonacciBranchTarget(TfContext ctx) {
        if (ctx.getDrivingCount() == null || ctx.getDrivingCount().getPivots() == null
                || ctx.getDrivingCount().getPivots().size() < 3) {
            return null;
        }

        List<EnrichedPivot> pivots = ctx.getDrivingCount().getPivots();
        EnrichedPivot first = pivots.get(0);
        EnrichedPivot second = pivots.get(1);
        EnrichedPivot last = pivots.get(pivots.size() - 1);
        double base = Math.abs(second.getPrice() - first.getPrice());
        if (base <= 0) return null;

        if (ctx.isBullishContext()) {
            return last.getPrice() + (base * 0.618);
        }
        return last.getPrice() - (base * 0.618);
    }

    @lombok.Data
    @lombok.Builder
    private static class BranchSnapshot {
        private String contextPath;
        private String currentState;
        private String primaryThesis;
        private List<BranchOption> options;
    }

    @lombok.Data
    @lombok.Builder
    private static class BranchOption {
        private String label;
        private String description;
        private Double target;
        private Double confidence;
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}
