package com.dtech.elliott.advanced.scenario.filter.score;

import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.common.enums.ExpectedMoveType;
import com.dtech.elliott.advanced.common.enums.StructureFamily;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.*;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultFamilyScorerTest {

    private DefaultFamilyScorer scorer;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        scorer = new DefaultFamilyScorer();
        config = FilterConfig.defaults();
    }

    private ScenarioCluster cluster(double structural, double confluence, double momentum) {
        ScenarioSignature sig = new ScenarioSignature("NIFTY", "15m",
                Direction.UP, ExpectedMoveType.CONTINUATION_UP, StructureFamily.CORRECTIVE,
                36L, true, true);
        return new ScenarioCluster(sig, List.of(),
                structural, 0.6, confluence, momentum,
                List.of(), List.of(), List.of(), List.of());
    }

    private ScenarioFamilyCandidate family(boolean triggerEligible, boolean decisionZone,
                                            int contradictingCount, ScenarioFamilyType type,
                                            double structural, double confluence) {
        List<ScenarioCluster> clusters = List.of(cluster(structural, confluence, 0.5));
        List<String> contradicting = java.util.stream.IntStream.range(0, contradictingCount)
                .mapToObj(i -> "PATTERN_" + i)
                .collect(java.util.stream.Collectors.toList());
        FamilyScore emptyScore = new FamilyScore(0, 0, 0, 0, 0, 0, 0, 0);
        return new ScenarioFamilyCandidate(
                "FAM-T", "NIFTY", "15m", type, Direction.UP,
                clusters, List.of(), List.of(), contradicting,
                1800.0, 0.0, 0.0, decisionZone, triggerEligible,
                triggerEligible && decisionZone,
                ScenarioStatus.ACTIVE_ALTERNATE, emptyScore,
                List.of(), List.of(), Map.of()
        );
    }

    @Test
    void high_confluence_low_ambiguity_ranks_higher() {
        var famA = family(true, true, 0, ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, 0.8, 0.8);
        var famB = family(false, false, 3, ScenarioFamilyType.NO_TRADE_NOISE, 0.3, 0.3);
        var scoredA = scorer.score(List.of(famA), config).get(0);
        var scoredB = scorer.score(List.of(famB), config).get(0);
        assertTrue(scoredA.score().finalRankScore() > scoredB.score().finalRankScore());
    }

    @Test
    void contradiction_penalty_reduces_score() {
        var famWithout = family(false, false, 0, ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, 0.6, 0.6);
        var famWith = family(false, false, 4, ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, 0.6, 0.6);
        var withoutScore = scorer.score(List.of(famWithout), config).get(0);
        var withScore = scorer.score(List.of(famWith), config).get(0);
        assertTrue(withScore.score().finalRankScore() < withoutScore.score().finalRankScore());
    }

    @Test
    void trigger_ready_improves_trade_utility() {
        var ready = family(true, true, 0, ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_COMPRESSION, 0.6, 0.6);
        var notReady = family(false, false, 0, ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_COMPRESSION, 0.6, 0.6);
        var readyScored = scorer.score(List.of(ready), config).get(0);
        var notReadyScored = scorer.score(List.of(notReady), config).get(0);
        assertTrue(readyScored.score().finalRankScore() > notReadyScored.score().finalRankScore());
    }

    @Test
    void no_trade_noise_gets_low_score() {
        var noise = family(false, false, 0, ScenarioFamilyType.NO_TRADE_NOISE, 0.3, 0.3);
        var scored = scorer.score(List.of(noise), config).get(0);
        assertTrue(scored.score().finalRankScore() < 0.3);
    }
}
