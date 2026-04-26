package com.dtech.ta.elliott.scenario;

import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import com.dtech.ta.elliott.scenario.ScoredScenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScoredScenarioAdapterTest {

    @Test
    void null_wave_scenario_throws() {
        ScoredScenario ss = ScoredScenario.builder().scenario(null).status(ScenarioStatus.ACTIVE_ALTERNATE).totalScore(30).build();
        assertThrows(IllegalArgumentException.class,
                () -> ScoredScenarioAdapter.toScenario(ss, "NIFTY", "15m"));
    }

    @Test
    void maps_direction_and_invalidation() {
        WaveScenario ws = WaveScenario.builder()
                .id("S1")
                .direction(WaveScenario.ScenarioDirection.BULLISH_CONTINUATION)
                .scenarioInvalidation(1800.0)
                .build();
        ScoredScenario ss = ScoredScenario.builder()
                .scenario(ws)
                .status(ScenarioStatus.LEADING)
                .totalScore(30)
                .build();
        Scenario result = ScoredScenarioAdapter.toScenario(ss, "NIFTY", "15m");
        assertEquals(WaveScenario.ScenarioDirection.BULLISH_CONTINUATION, result.direction());
        assertEquals(1800.0, result.invalidationLevel());
        assertEquals(ScenarioStatus.LEADING, result.status());
    }

    @Test
    void null_list_returns_empty() {
        assertTrue(ScoredScenarioAdapter.toScenarios(null, "X", "15m").isEmpty());
    }
}
