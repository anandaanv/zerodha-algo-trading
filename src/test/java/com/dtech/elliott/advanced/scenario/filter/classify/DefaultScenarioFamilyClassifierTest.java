package com.dtech.elliott.advanced.scenario.filter.classify;

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

class DefaultScenarioFamilyClassifierTest {

    private DefaultScenarioFamilyClassifier classifier;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        classifier = new DefaultScenarioFamilyClassifier();
        config = FilterConfig.defaults();
    }

    private ScenarioCluster cluster(Direction dir, ExpectedMoveType emt, StructureFamily sf,
                                     boolean triggerEligible, String... patterns) {
        ScenarioSignature sig = new ScenarioSignature("NIFTY", "15m", dir, emt, sf, 36L, false, triggerEligible);
        NormalizedScenario member = new NormalizedScenario(
                "S1", "NIFTY", "15m", dir, emt, sf,
                List.of(), List.of(patterns), 1800.0, 25.0, 0.0,
                0.5, 0.5, 0.5, 0.2, 0.5, false, triggerEligible,
                ScenarioStatus.ACTIVE_ALTERNATE, Map.of(), List.of(), List.of()
        );
        return new ScenarioCluster(sig, List.of(member),
                0.5, 0.5, 0.5, 0.5,
                List.of(patterns), List.of(), List.of(), List.of()
        );
    }

    @Test
    void triangle_wedge_compression_family() {
        var c = cluster(Direction.NEUTRAL, ExpectedMoveType.BREAKOUT_PENDING,
                StructureFamily.COMPRESSIVE, false, "SYMMETRICAL_TRIANGLE", "ASCENDING_CHANNEL");
        var result = classifier.classify(List.of(c), config);
        assertEquals(ScenarioFamilyType.SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT, result.get(0).familyType());
    }

    @Test
    void falling_wedge_bullish_continuation() {
        var c = cluster(Direction.UP, ExpectedMoveType.CONTINUATION_UP,
                StructureFamily.COMPRESSIVE, true, "FALLING_WEDGE");
        var result = classifier.classify(List.of(c), config);
        assertEquals(ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_COMPRESSION, result.get(0).familyType());
    }

    @Test
    void corrective_up_family() {
        var c = cluster(Direction.UP, ExpectedMoveType.CONTINUATION_UP,
                StructureFamily.CORRECTIVE, true, "ELLIOTT_ZIGZAG");
        var result = classifier.classify(List.of(c), config);
        assertEquals(ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, result.get(0).familyType());
    }

    @Test
    void terminal_bearish_family() {
        var c = cluster(Direction.DOWN, ExpectedMoveType.REVERSAL_DOWN,
                StructureFamily.TERMINAL, false, "HEAD_AND_SHOULDERS", "DOUBLE_TOP");
        var result = classifier.classify(List.of(c), config);
        assertEquals(ScenarioFamilyType.BEARISH_REVERSAL_AFTER_TERMINAL_PUSH, result.get(0).familyType());
    }

    @Test
    void low_conviction_seed() {
        var c = cluster(Direction.UP, ExpectedMoveType.REVERSAL_UP,
                StructureFamily.AMBIGUOUS, false, "DOUBLE_BOTTOM");
        var result = classifier.classify(List.of(c), config);
        assertEquals(ScenarioFamilyType.LOW_CONVICTION_REVERSAL_SEED, result.get(0).familyType());
    }

    @Test
    void empty_clusters_returns_empty() {
        assertTrue(classifier.classify(List.of(), config).isEmpty());
    }
}
