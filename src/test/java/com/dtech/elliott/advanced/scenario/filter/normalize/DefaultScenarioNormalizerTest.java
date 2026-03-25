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
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultScenarioNormalizerTest {

    private DefaultScenarioNormalizer normalizer;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        normalizer = new DefaultScenarioNormalizer();
        config = FilterConfig.defaults();
    }

    private PatternMatch pm(PatternType type, double confidence) {
        return PatternMatch.builder().type(type).confidence(confidence).build();
    }

    @Test
    void bullish_continuation_maps_to_UP() {
        Scenario s = new Scenario("S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, null, ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(pm(PatternType.FALLING_WEDGE, 0.7)), List.of(), List.of(),
                Map.of(), List.of(), List.of());
        NormalizedScenario result = normalizer.normalize(List.of(s), config).get(0);
        assertEquals(Direction.UP, result.directionalBias());
        assertEquals(ExpectedMoveType.CONTINUATION_UP, result.expectedMoveType());
    }

    @Test
    void range_resolution_maps_to_NEUTRAL_and_BREAKOUT_PENDING() {
        Scenario s = new Scenario("S2", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.RANGE_RESOLUTION,
                1700.0, null, ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(pm(PatternType.SYMMETRICAL_TRIANGLE, 0.6)), List.of(), List.of(),
                Map.of(), List.of(), List.of());
        NormalizedScenario result = normalizer.normalize(List.of(s), config).get(0);
        assertEquals(Direction.NEUTRAL, result.directionalBias());
        assertEquals(ExpectedMoveType.BREAKOUT_PENDING, result.expectedMoveType());
    }

    @Test
    void structure_family_for_zigzag() {
        Scenario s = new Scenario("S3", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1750.0, null, ScenarioStatus.ACTIVE_ALTERNATE, 60.0,
                List.of(), List.of(), List.of(pm(PatternType.ELLIOTT_ZIGZAG, 0.8)),
                Map.of(), List.of(), List.of());
        NormalizedScenario result = normalizer.normalize(List.of(s), config).get(0);
        assertEquals(StructureFamily.CORRECTIVE, result.dominantStructureFamily());
    }

    @Test
    void compressive_structure_for_triangle() {
        Scenario s = new Scenario("S4", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.RANGE_RESOLUTION,
                1700.0, null, ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(), List.of(), List.of(pm(PatternType.SYMMETRICAL_TRIANGLE, 0.6)),
                Map.of(), List.of(), List.of());
        NormalizedScenario result = normalizer.normalize(List.of(s), config).get(0);
        assertEquals(StructureFamily.COMPRESSIVE, result.dominantStructureFamily());
    }

    @Test
    void null_input_returns_empty() {
        assertTrue(normalizer.normalize(null, config).isEmpty());
    }
}
