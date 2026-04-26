package com.dtech.ta.elliott.filter.prune;

import com.dtech.ta.elliott.scenario.Scenario;
import com.dtech.ta.elliott.filter.config.FilterConfig;
import com.dtech.ta.elliott.PatternMatch;
import com.dtech.ta.elliott.PatternType;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultHardPrunerTest {

    private DefaultHardPruner pruner;
    private FilterConfig config;

    @BeforeEach
    void setUp() {
        pruner = new DefaultHardPruner();
        config = FilterConfig.defaults();
    }

    private Scenario valid() {
        PatternMatch pm = PatternMatch.builder()
                .type(PatternType.FALLING_WEDGE)
                .confidence(0.7)
                .build();
        return new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "wave structure", ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
    }

    @Test
    void invalidated_scenario_pruned() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.INVALIDATED, 50.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(0, pruner.prune(List.of(s), config).size());
    }

    @Test
    void completed_scenario_pruned() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.COMPLETED, 50.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(0, pruner.prune(List.of(s), config).size());
    }

    @Test
    void zero_invalidation_pruned() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                0.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(0, pruner.prune(List.of(s), config).size());
    }

    @Test
    void nan_invalidation_pruned() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                Double.NaN, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(0, pruner.prune(List.of(s), config).size());
    }

    @Test
    void null_direction_pruned() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                null,
                1800.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(0, pruner.prune(List.of(s), config).size());
    }

    @Test
    void no_patterns_pruned() {
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(0, pruner.prune(List.of(s), config).size());
    }

    @Test
    void low_score_pruned() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        // totalScore=10 → 10/100=0.10 < floor 0.20
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 10.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(0, pruner.prune(List.of(s), config).size());
    }

    @Test
    void valid_scenario_kept() {
        assertEquals(1, pruner.prune(List.of(valid()), config).size());
    }

    @Test
    void null_scenarios_returns_empty() {
        assertEquals(0, pruner.prune(null, config).size());
    }

    @Test
    void empty_scenarios_returns_empty() {
        assertEquals(0, pruner.prune(List.of(), config).size());
    }

    @Test
    void mixed_scenarios_prunes_correctly() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();

        Scenario valid1 = valid();
        Scenario invalidated = new Scenario(
                "S2", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.INVALIDATED, 50.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        Scenario lowScore = new Scenario(
                "S3", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 5.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        Scenario valid2 = new Scenario(
                "S4", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BEARISH_REVERSAL,
                2000.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 60.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );

        List<Scenario> input = List.of(valid1, invalidated, lowScore, valid2);
        List<Scenario> result = pruner.prune(input, config);

        assertEquals(2, result.size());
        assertTrue(result.stream().anyMatch(s -> s.id().equals("S1")));
        assertTrue(result.stream().anyMatch(s -> s.id().equals("S4")));
    }

    @Test
    void scenario_at_exact_score_floor_kept() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        // totalScore=20 → 20/100=0.20 = floor 0.20 (should pass: not <)
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 20.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(1, pruner.prune(List.of(s), config).size());
    }

    @Test
    void scenario_below_score_floor_pruned() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        // totalScore=19 → 19/100=0.19 < floor 0.20
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 19.0,
                List.of(pm), List.of(), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(0, pruner.prune(List.of(s), config).size());
    }

    @Test
    void conflicting_patterns_satisfies_pattern_requirement() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(), List.of(pm), List.of(), Map.of(), List.of(), List.of()
        );
        assertEquals(1, pruner.prune(List.of(s), config).size());
    }

    @Test
    void relevant_patterns_satisfies_pattern_requirement() {
        PatternMatch pm = PatternMatch.builder().type(PatternType.FALLING_WEDGE).confidence(0.7).build();
        Scenario s = new Scenario(
                "S1", "NIFTY", "15m",
                WaveScenario.ScenarioDirection.BULLISH_CONTINUATION,
                1800.0, "reason", ScenarioStatus.ACTIVE_ALTERNATE, 50.0,
                List.of(), List.of(), List.of(pm), Map.of(), List.of(), List.of()
        );
        assertEquals(1, pruner.prune(List.of(s), config).size());
    }
}
