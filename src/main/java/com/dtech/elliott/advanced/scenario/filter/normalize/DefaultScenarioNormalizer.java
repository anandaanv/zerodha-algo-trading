package com.dtech.elliott.advanced.scenario.filter.normalize;

import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.common.enums.ExpectedMoveType;
import com.dtech.elliott.advanced.common.enums.StructureFamily;
import com.dtech.elliott.advanced.domain.scenario.Scenario;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.NormalizedScenario;
import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.PatternType;
import com.dtech.ta.elliott.WaveScenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class DefaultScenarioNormalizer implements ScenarioNormalizer {

    private static final Logger log = LoggerFactory.getLogger(DefaultScenarioNormalizer.class);

    // Corrective pattern types
    private static final Set<PatternType> CORRECTIVE_TYPES = Set.of(
            PatternType.ELLIOTT_ZIGZAG, PatternType.ELLIOTT_FLAT, PatternType.ELLIOTT_EXPANDED_FLAT,
            PatternType.DOUBLE_BOTTOM, PatternType.DOUBLE_TOP,
            PatternType.HEAD_AND_SHOULDERS, PatternType.INVERTED_HEAD_AND_SHOULDERS,
            PatternType.TRIPLE_BOTTOM, PatternType.TRIPLE_TOP,
            PatternType.CUP_AND_HANDLE, PatternType.ROUNDING_BOTTOM
    );

    // Terminal pattern types
    private static final Set<PatternType> TERMINAL_TYPES = Set.of(
            PatternType.RISING_WEDGE, PatternType.FALLING_WEDGE
    );

    // Compressive pattern types
    private static final Set<PatternType> COMPRESSIVE_TYPES = Set.of(
            PatternType.SYMMETRICAL_TRIANGLE, PatternType.ASCENDING_TRIANGLE, PatternType.DESCENDING_TRIANGLE,
            PatternType.ASCENDING_CHANNEL, PatternType.DESCENDING_CHANNEL, PatternType.HORIZONTAL_CHANNEL
    );

    @Override
    public List<NormalizedScenario> normalize(List<Scenario> scenarios, FilterConfig config) {
        if (scenarios == null || scenarios.isEmpty()) return List.of();
        return scenarios.stream()
                .map(s -> normalizeOne(s, config))
                .toList();
    }

    private NormalizedScenario normalizeOne(Scenario s, FilterConfig config) {
        Direction directionalBias = resolveDirection(s.direction());
        ExpectedMoveType expectedMoveType = resolveExpectedMove(s.direction());
        StructureFamily dominantStructureFamily = resolveStructureFamily(s.relevantPatterns(), directionalBias);

        double structuralScore = Math.min(s.totalScore() / 100.0, 1.0);

        double confluenceScore = s.decisionPoints() != null && !s.decisionPoints().isEmpty()
                ? Math.min(0.5 + 0.1 * s.decisionPoints().size(), 1.0)
                : 0.3;

        double momentumScore = computeMomentumScore(s, directionalBias);

        boolean decisionZoneNearby = s.decisionPoints() != null && !s.decisionPoints().isEmpty();

        boolean triggerEligible = isTriggerEligible(s);

        double ambiguityScore = Math.min(
                (s.conflictingPatterns() != null ? s.conflictingPatterns().size() : 0) * 0.15, 0.9);

        double tradeUtilityScore = triggerEligible ? 0.7 : 0.3;

        List<String> supportingPatternTypes = deduplicated(
                s.alignedPatterns() != null
                        ? s.alignedPatterns().stream().map(p -> p.getType().name()).toList()
                        : List.of()
        );

        List<String> supportingStructureTypes = deduplicated(
                s.relevantPatterns() != null
                        ? s.relevantPatterns().stream()
                            .filter(p -> s.alignedPatterns() == null || !s.alignedPatterns().contains(p))
                            .map(p -> p.getType().name())
                            .toList()
                        : List.of()
        );

        double targetReferenceLevel = 0.0;
        if (s.alignedPatterns() != null) {
            targetReferenceLevel = s.alignedPatterns().stream()
                    .filter(p -> p.getTarget() != null)
                    .mapToDouble(PatternMatch::getTarget)
                    .findFirst()
                    .orElse(0.0);
        }

        double invalidationTolerance = config.invalidationBucketSize() * 0.5;

        List<String> explanation = deduplicated(s.statusReasons() != null ? s.statusReasons() : List.of());
        List<String> reasonCodes = List.of(
                "DIRECTION:" + s.direction().name(),
                "STATUS:" + s.status().name()
        );

        Map<String, Double> scoreComponents = new LinkedHashMap<>();
        scoreComponents.put("structural", structuralScore);
        scoreComponents.put("confluence", confluenceScore);
        scoreComponents.put("momentum", momentumScore);
        scoreComponents.put("ambiguity", ambiguityScore);

        return new NormalizedScenario(
                s.id(), s.symbol(), s.anchorTimeframe(),
                directionalBias, expectedMoveType, dominantStructureFamily,
                supportingStructureTypes, supportingPatternTypes,
                s.invalidationLevel(), invalidationTolerance, targetReferenceLevel,
                confluenceScore, structuralScore, momentumScore, ambiguityScore, tradeUtilityScore,
                decisionZoneNearby, triggerEligible, s.status(),
                Collections.unmodifiableMap(scoreComponents),
                explanation, reasonCodes
        );
    }

    private Direction resolveDirection(WaveScenario.ScenarioDirection dir) {
        if (dir == null) return Direction.NEUTRAL;
        return switch (dir) {
            case BULLISH_CONTINUATION, BULLISH_REVERSAL -> Direction.UP;
            case BEARISH_CONTINUATION, BEARISH_REVERSAL -> Direction.DOWN;
            case RANGE_RESOLUTION -> Direction.NEUTRAL;
        };
    }

    private ExpectedMoveType resolveExpectedMove(WaveScenario.ScenarioDirection dir) {
        if (dir == null) return ExpectedMoveType.UNKNOWN;
        return switch (dir) {
            case BULLISH_CONTINUATION -> ExpectedMoveType.CONTINUATION_UP;
            case BEARISH_CONTINUATION -> ExpectedMoveType.CONTINUATION_DOWN;
            case BULLISH_REVERSAL -> ExpectedMoveType.REVERSAL_UP;
            case BEARISH_REVERSAL -> ExpectedMoveType.REVERSAL_DOWN;
            case RANGE_RESOLUTION -> ExpectedMoveType.BREAKOUT_PENDING;
        };
    }

    private StructureFamily resolveStructureFamily(List<PatternMatch> patterns, Direction dir) {
        if (patterns == null || patterns.isEmpty()) {
            return dir == Direction.NEUTRAL ? StructureFamily.AMBIGUOUS : StructureFamily.IMPULSIVE;
        }
        Set<PatternType> types = patterns.stream().map(PatternMatch::getType).collect(Collectors.toSet());
        if (!Collections.disjoint(types, CORRECTIVE_TYPES)) return StructureFamily.CORRECTIVE;
        if (!Collections.disjoint(types, TERMINAL_TYPES)) return StructureFamily.TERMINAL;
        if (!Collections.disjoint(types, COMPRESSIVE_TYPES)) return StructureFamily.COMPRESSIVE;
        if (dir == Direction.UP || dir == Direction.DOWN) return StructureFamily.IMPULSIVE;
        return StructureFamily.AMBIGUOUS;
    }

    private double computeMomentumScore(Scenario s, Direction dir) {
        if (s.indicatorAlignment() == null || s.indicatorAlignment().isEmpty()) return 0.5;
        long total = s.indicatorAlignment().size();
        long aligned = s.indicatorAlignment().values().stream()
                .filter(v -> dir == Direction.UP
                        ? v.toUpperCase().contains("BULLISH")
                        : v.toUpperCase().contains("BEARISH"))
                .count();
        return total == 0 ? 0.5 : Math.min((double) aligned / total, 1.0);
    }

    private boolean isTriggerEligible(Scenario s) {
        boolean statusOk = s.status() == com.dtech.ta.elliott.scenario.ScenarioStatus.LEADING
                || s.status() == com.dtech.ta.elliott.scenario.ScenarioStatus.ACTIVE_ALTERNATE;
        boolean hasConfidentPattern = s.alignedPatterns() != null
                && s.alignedPatterns().stream().anyMatch(p -> p.getConfidence() > 0.5);
        return statusOk && hasConfidentPattern;
    }

    private List<String> deduplicated(List<String> list) {
        return new ArrayList<>(new LinkedHashSet<>(list));
    }
}
