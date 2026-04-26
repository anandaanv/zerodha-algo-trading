package com.dtech.ta.elliott.filter.classify;

import com.dtech.ta.elliott.model.Direction;
import com.dtech.ta.elliott.model.ExpectedMoveType;
import com.dtech.ta.elliott.model.StructureFamily;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.filter.domain.*;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class DefaultScenarioFamilyClassifier implements ScenarioFamilyClassifier {

    private static final Logger log = LoggerFactory.getLogger(DefaultScenarioFamilyClassifier.class);
    private static final AtomicInteger idCounter = new AtomicInteger(0);

    @Override
    public List<ScenarioFamilyCandidate> classify(List<ScenarioCluster> clusters, FilterConfig config) {
        if (clusters == null || clusters.isEmpty()) return List.of();
        return clusters.stream()
                .map(c -> classifyCluster(c, config))
                .toList();
    }

    private ScenarioFamilyCandidate classifyCluster(ScenarioCluster cluster, FilterConfig config) {
        ScenarioSignature sig = cluster.signature();
        List<String> patterns = cluster.mergedSupportingPatterns();
        Direction dir = sig.directionalBias();
        ExpectedMoveType emt = sig.expectedMoveType();
        StructureFamily sf = sig.dominantStructureFamily();

        ScenarioFamilyType familyType = determineFamilyType(patterns, dir, emt, sf, sig.triggerEligible());

        // Average invalidation from cluster members
        double primaryInvalidation = cluster.members().stream()
                .mapToDouble(NormalizedScenario::primaryInvalidationLevel)
                .average()
                .orElse(0.0);

        // Status from first member, fallback to ACTIVE_ALTERNATE
        ScenarioStatus status = cluster.members().isEmpty()
                ? ScenarioStatus.ACTIVE_ALTERNATE
                : cluster.members().get(0).sourceStatus();

        String id = "FAM-" + idCounter.incrementAndGet();

        FamilyScore emptyScore = new FamilyScore(0, 0, 0, 0, 0, 0, 0, 0);

        log.debug("Classified cluster as {} family type", familyType);

        return new ScenarioFamilyCandidate(
                id, sig.symbol(), sig.anchorTimeframe(),
                familyType, dir,
                List.of(cluster),
                cluster.mergedSupportingStructures(),
                patterns,
                List.of(), // contradicting patterns — filled in later
                primaryInvalidation,
                0.0, // confirmationLevel
                0.0, // projectedTargetReference
                sig.decisionZoneNearby(),
                sig.triggerEligible(),
                sig.triggerEligible() && sig.decisionZoneNearby(), // tradableNow
                status,
                emptyScore,
                cluster.mergedExplanation(),
                cluster.mergedReasonCodes(),
                Map.of()
        );
    }

    private ScenarioFamilyType determineFamilyType(List<String> patterns, Direction dir,
                                                    ExpectedMoveType emt, StructureFamily sf,
                                                    boolean triggerEligible) {
        // Rule 1 — Compression
        if (ClassificationHelper.containsCompressionPatterns(patterns)
                && emt == ExpectedMoveType.BREAKOUT_PENDING) {
            return ScenarioFamilyType.SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT;
        }

        // Rule 2 — Bullish continuation after compression
        if (dir == Direction.UP
                && ClassificationHelper.containsBullishCompressionPatterns(patterns)
                && emt == ExpectedMoveType.CONTINUATION_UP) {
            return ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_COMPRESSION;
        }

        // Rule 3 — Bearish breakdown after compression
        if (dir == Direction.DOWN
                && ClassificationHelper.containsBearishCompressionPatterns(patterns)) {
            return ScenarioFamilyType.BEARISH_BREAKDOWN_AFTER_COMPRESSION;
        }

        // Rule 4 — Bullish continuation after correction
        if (sf == StructureFamily.CORRECTIVE
                && dir == Direction.UP
                && emt == ExpectedMoveType.CONTINUATION_UP) {
            return ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION;
        }

        // Rule 5 — Bearish reversal after terminal push
        if (ClassificationHelper.containsTerminalPatterns(patterns)
                && dir == Direction.DOWN
                && emt == ExpectedMoveType.REVERSAL_DOWN) {
            return ScenarioFamilyType.BEARISH_REVERSAL_AFTER_TERMINAL_PUSH;
        }

        // Rule 6 — Ongoing corrective complexity
        if (sf == StructureFamily.CORRECTIVE && dir == Direction.NEUTRAL) {
            return ScenarioFamilyType.ONGOING_CORRECTIVE_COMPLEXITY;
        }

        // Rule 7 — Low conviction reversal seed
        if ((ClassificationHelper.containsBullishReversalSeeds(patterns)
                || ClassificationHelper.containsBearishReversalSeeds(patterns))
                && !triggerEligible) {
            return ScenarioFamilyType.LOW_CONVICTION_REVERSAL_SEED;
        }

        // Default
        return ScenarioFamilyType.NO_TRADE_NOISE;
    }
}
