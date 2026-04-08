package com.dtech.ta.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import com.dtech.kitecon.service.copilot.dto.TrendSegment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.time.Instant;
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
    private final com.dtech.ta.trendline.VirginTrendlineDetector virginTrendlineDetector;

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

        // Current price from primary series (used for activation status and branch probabilities)
        BarSeries primaryBarSeries = seriesByTf.get(primaryTf);
        double currentPrice = (primaryBarSeries != null && primaryBarSeries.getBarCount() > 0)
                ? primaryBarSeries.getLastBar().getClosePrice().doubleValue() : 0.0;

        // Layer 7: Scenarios
        List<WaveScenario> scenarios = scenarioBuilder.build(waveCounts, patterns, primaryTf, currentPrice);

        // Phase 2b: segment proportionality bonus on wave counts (before scenario ranking)
        applySegmentProportionalityBonus(waveCounts, structureByTf);

        // TfContext: top-down extraction
        Map<String, TfContext> tfContexts = buildTfContexts(tfOrder, waveCounts);

        // Phase 2a: enrich TfContext with segment data (correctionOrigin, segmentCount, narrative)
        enrichTfContextsWithSegments(tfContexts, structureByTf);

        // Bottom-up: child TF confirmations boost parent scores
        applyBottomUpBoosts(tfContexts, tfOrder, waveCounts);

        // Detect virgin trendlines from the top wave count per primary timeframe
        List<com.dtech.ta.trendline.VirginTrendline> virginTrendlines = new java.util.ArrayList<>();
        waveCounts.stream()
            .filter(wc -> primaryTf.equals(wc.getPrimaryTimeframe()))
            .max(java.util.Comparator.comparingInt(WaveCount::totalScore))
            .ifPresent(topCount -> {
                BarSeries primarySeries = seriesByTf.get(primaryTf);
                if (primarySeries != null) {
                    virginTrendlines.addAll(virginTrendlineDetector.detect(topCount, primarySeries));
                }
            });

        // Cross-TF narrative
        String narrative = buildCrossTfNarrative(tfOrder, tfContexts, gapsByTf, patterns);
        String nestedBranchNarrative = buildNestedBranchNarrative(tfOrder, tfContexts, patterns, scenarios);
        List<NestedWaveContext> nestedCorrectiveContexts = nestedCorrectiveContextBuilder.build(
                tfOrder, primaryTf, tfContexts, enrichedByTf, patterns, currentPrice, waveCounts);

        return ElliottWaveAnalysis.builder()
            .scenarios(scenarios).allPatterns(patterns)
            .enrichedPivots(enrichedByTf).waveCounts(waveCounts)
            .primaryTimeframe(primaryTf).timeframes(tfOrder)
            .gapsByTf(gapsByTf).significantGaps(significantGaps)
            .tfContexts(tfContexts).crossTfNarrative(narrative)
            .nestedBranchNarrative(nestedBranchNarrative)
            .nestedCorrectiveContexts(nestedCorrectiveContexts)
            .virginTrendlines(virginTrendlines)
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
        // Deduplicate by (type, timeframe, status) — only print one representative per unique combo
        java.util.Set<String> seenPatternKeys = new java.util.LinkedHashSet<>();
        for (PatternMatch pm : patterns) {
            if (pm.getWaveContextHints() == null || pm.getWaveContextHints().isEmpty()) continue;
            String key = pm.getType() + "|" + pm.getTimeframe() + "|" + pm.getStatus();
            if (!seenPatternKeys.add(key)) continue; // skip duplicate type+tf+status combos
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

    // ─── Phase 2a: TfContext enrichment from trend segments ───────────────────

    /**
     * Enriches each TfContext with structural data derived from trend segments:
     * - Sets correctionOrigin if the ongoing segment is a downtrend and wave count didn't set it
     * - Overrides bullishContext when structural trend evidence is independently strong
     * - Sets segmentCount (rough wave-position proxy)
     * - Appends segment narrative to existing TfContext narrative
     */
    private void enrichTfContextsWithSegments(Map<String, TfContext> tfContexts,
                                               Map<String, MarketStructureData> structureByTf) {
        for (Map.Entry<String, TfContext> entry : tfContexts.entrySet()) {
            String tf = entry.getKey();
            TfContext ctx = entry.getValue();
            MarketStructureData msd = structureByTf.get(tf);
            if (msd == null || msd.getTrendSegments() == null || msd.getTrendSegments().isEmpty()) continue;

            List<TrendSegment> segments = msd.getTrendSegments();
            TrendSegment current = segments.get(segments.size() - 1);

            // Segment count — rough wave position proxy
            ctx.setSegmentCount(segments.size());

            // Fill correctionOrigin from segment if wave count didn't set it
            if (ctx.getCorrectionOrigin() == null
                    && current.getDirection() == TrendSegment.Direction.DOWNTREND) {
                ctx.setCorrectionOrigin(current.getStartPrice());
            }

            // Override bullishContext when structural trend independently confirms uptrend
            if (!ctx.isBullishContext()
                    && msd.getTrendDirection() == MarketStructureData.TrendDirection.UPTREND
                    && msd.getTrendStrength() >= 2) {
                ctx.setBullishContext(true);
            }

            // Append segment narrative
            String segNarrative = buildSegmentNarrative(tf, segments);
            if (segNarrative != null && ctx.getNarrative() != null) {
                ctx.setNarrative(ctx.getNarrative() + " | " + segNarrative);
            }
        }
    }

    private String buildSegmentNarrative(String tf, List<TrendSegment> segments) {
        if (segments.isEmpty()) return null;
        TrendSegment current = segments.get(segments.size() - 1);
        int count = segments.size();
        String pos = switch (count) {
            case 1 -> "1 segment — early W1/A territory";
            case 2 -> "2 segments — W1-2 or A-B";
            case 3 -> "3 segments — W1-2-3 or ABC";
            case 4 -> "4 segments — W1-2-3-4 or post-ABC";
            case 5 -> "5 segments — full impulse candidate";
            default -> count + " segments";
        };
        return String.format("%s: %s | current %s %+.1f%% over %d pivots",
                tf, pos, current.getDirection(), current.getPriceChangePct(), current.getPivotCount());
    }

    // ─── Phase 2b: segment proportionality bonus on wave counts ──────────────

    /**
     * Post-processing pass: awards a proportionality bonus (max +15 to indicatorScore)
     * to IMPULSE wave counts where the segment structure matches Elliott expectations:
     *
     *  +6  W3 is in the longest segment (highest absolute price change among all segments)
     *  +5  W2 and W4 correction segments have different depths (>20% difference — alternation)
     *  +4  W5 segment is weaker than W3 segment (momentum divergence — terminal signal)
     */
    private void applySegmentProportionalityBonus(List<WaveCount> waveCounts,
                                                   Map<String, MarketStructureData> structureByTf) {
        for (WaveCount wc : waveCounts) {
            if (wc.getWaveType() != WaveCount.WaveType.IMPULSE) continue;
            if (wc.getPivots() == null || wc.getPivots().size() < 5) continue;

            MarketStructureData msd = structureByTf.get(wc.getPrimaryTimeframe());
            if (msd == null || msd.getTrendSegments() == null || msd.getTrendSegments().isEmpty()) continue;

            List<TrendSegment> segments = msd.getTrendSegments();
            List<EnrichedPivot> pivots = wc.getPivots();

            // Locate the segment each wave body falls in (by midpoint timestamp)
            TrendSegment w1Seg = segmentAt(segments, midpoint(pivots.get(0), pivots.get(1)));
            TrendSegment w2Seg = segmentAt(segments, midpoint(pivots.get(1), pivots.get(2)));
            TrendSegment w3Seg = segmentAt(segments, midpoint(pivots.get(2), pivots.get(3)));
            TrendSegment w4Seg = segmentAt(segments, midpoint(pivots.get(3), pivots.get(4)));
            TrendSegment w5Seg = pivots.size() >= 6
                    ? segmentAt(segments, midpoint(pivots.get(4), pivots.get(5)))
                    : null;

            int bonus = 0;

            // +6: W3 is in the segment with the largest absolute price change
            if (w3Seg != null) {
                double w3Change = Math.abs(w3Seg.getPriceChangePct());
                boolean w3IsLongest = segments.stream()
                        .allMatch(s -> s == w3Seg || Math.abs(s.getPriceChangePct()) <= w3Change);
                if (w3IsLongest) bonus += 6;
            }

            // +5: W2 and W4 have different correction depths (>20% relative diff — alternation)
            if (w2Seg != null && w4Seg != null) {
                double d2 = Math.abs(w2Seg.getPriceChangePct());
                double d4 = Math.abs(w4Seg.getPriceChangePct());
                double deeper = Math.max(d2, d4);
                if (deeper > 0 && Math.abs(d2 - d4) / deeper > 0.20) bonus += 5;
            }

            // +4: W5 segment weaker than W3 (divergence)
            if (w3Seg != null && w5Seg != null
                    && Math.abs(w5Seg.getPriceChangePct()) < Math.abs(w3Seg.getPriceChangePct())) {
                bonus += 4;
            }

            if (bonus > 0) {
                wc.setProportionalityBonus(Math.min(wc.getProportionalityBonus() + bonus, 15));
            }
        }
    }

    /** Returns the segment whose time range contains the given instant (by midpoint lookup). */
    private TrendSegment segmentAt(List<TrendSegment> segments, Instant instant) {
        for (TrendSegment seg : segments) {
            Instant start = seg.getStartTime();
            Instant end   = seg.getEndTime() != null ? seg.getEndTime() : Instant.MAX;
            if (!instant.isBefore(start) && !instant.isAfter(end)) return seg;
        }
        return null;
    }

    /** Midpoint instant between two enriched pivots. */
    private Instant midpoint(EnrichedPivot a, EnrichedPivot b) {
        return Instant.ofEpochSecond(
                (a.getTimestamp().getEpochSecond() + b.getTimestamp().getEpochSecond()) / 2);
    }

    private String fmt(double v) { return String.format("%.2f", v); }
}
