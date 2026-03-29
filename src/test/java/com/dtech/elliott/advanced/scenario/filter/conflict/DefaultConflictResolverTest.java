package com.dtech.elliott.advanced.scenario.filter.conflict;

import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.scenario.filter.config.FilterConfig;
import com.dtech.elliott.advanced.scenario.filter.domain.*;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultConflictResolverTest {

    private DefaultConflictResolver resolver;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        resolver = new DefaultConflictResolver();
        config = FilterConfig.defaults();
    }

    private ScenarioFamilyCandidate family(String id, Direction dir, ScenarioFamilyType type) {
        FamilyScore score = new FamilyScore(0.5, 0.5, 0.5, 0.2, 0.1, 0.6, 0.5, 0.55);
        return new ScenarioFamilyCandidate(
                id, "NIFTY", "15m", type, dir,
                List.of(), List.of(), List.of(), List.of(),
                1800.0, 0.0, 0.0, false, false, false,
                ScenarioStatus.ACTIVE_ALTERNATE, score,
                List.of(), List.of(), Map.of()
        );
    }

    @Test
    void bullish_and_bearish_creates_conflict() {
        var bullish = family("F1", Direction.UP, ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION);
        var bearish = family("F2", Direction.DOWN, ScenarioFamilyType.BEARISH_REVERSAL_AFTER_TERMINAL_PUSH);
        ScenarioConflictSet result = resolver.resolve(List.of(bullish, bearish), config);
        assertEquals("BULLISH_VS_BEARISH", result.dominantConflictMode());
        assertEquals(1, result.bullishFamilies().size());
        assertEquals(1, result.bearishFamilies().size());
    }

    @Test
    void neutral_only_creates_compressive_mode() {
        var n1 = family("F1", Direction.NEUTRAL, ScenarioFamilyType.SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT);
        var n2 = family("F2", Direction.NEUTRAL, ScenarioFamilyType.ONGOING_CORRECTIVE_COMPLEXITY);
        ScenarioConflictSet result = resolver.resolve(List.of(n1, n2), config);
        assertEquals("TRENDING_VS_COMPRESSIVE", result.dominantConflictMode());
    }

    @Test
    void empty_input_returns_empty_conflict_set() {
        ScenarioConflictSet result = resolver.resolve(List.of(), config);
        assertTrue(result.bullishFamilies().isEmpty());
        assertTrue(result.bearishFamilies().isEmpty());
    }
}
