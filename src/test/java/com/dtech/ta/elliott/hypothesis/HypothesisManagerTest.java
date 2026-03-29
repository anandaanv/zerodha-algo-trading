package com.dtech.ta.elliott.hypothesis;

import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HypothesisManagerTest {

    private HypothesisManager manager;

    @BeforeEach
    void setUp() {
        manager = new HypothesisManager();
    }

    private ScoredScenario scoredScenario(String scenarioId, ScenarioStatus status) {
        WaveScenario scenario = WaveScenario.builder()
                .id(scenarioId)
                .direction(WaveScenario.ScenarioDirection.BULLISH_CONTINUATION)
                .hypotheses(new ArrayList<>())
                .build();
        return ScoredScenario.builder()
                .scenario(scenario)
                .status(status)
                .totalScore(20.0)
                .statusReasons(new ArrayList<>())
                .build();
    }

    @Test
    void first_call_creates_snapshot() {
        var scenarios = List.of(
                scoredScenario("S1", ScenarioStatus.LEADING),
                scoredScenario("S2", ScenarioStatus.ACTIVE_ALTERNATE)
        );
        HypothesisSnapshot snap = manager.update("INFY", "1D", scenarios);
        assertNotNull(snap);
        assertEquals(2, snap.getScoredScenarios().size());
        assertEquals(2, snap.getTransitions().size()); // both are new (fromStatus=null)
        snap.getTransitions().forEach(t -> assertNull(t.getFromStatus()));
    }

    @Test
    void promotion_detected() {
        // First call: S1 = ACTIVE_ALTERNATE
        manager.update("INFY", "1D", List.of(scoredScenario("S1", ScenarioStatus.ACTIVE_ALTERNATE)));
        // Second call: S1 = LEADING
        HypothesisSnapshot snap = manager.update("INFY", "1D", List.of(scoredScenario("S1", ScenarioStatus.LEADING)));

        assertTrue(snap.getTransitions().stream().anyMatch(t ->
                t.getScenarioId().equals("S1")
                && t.getFromStatus() == ScenarioStatus.ACTIVE_ALTERNATE
                && t.getToStatus() == ScenarioStatus.LEADING));
    }

    @Test
    void invalidation_detected_when_scenario_dropped() {
        manager.update("INFY", "1D", List.of(scoredScenario("S1", ScenarioStatus.LEADING)));
        // Second call: S1 missing
        HypothesisSnapshot snap = manager.update("INFY", "1D", List.of(scoredScenario("S2", ScenarioStatus.LEADING)));

        assertTrue(snap.getTransitions().stream().anyMatch(t ->
                t.getScenarioId().equals("S1")
                && t.getFromStatus() == ScenarioStatus.LEADING
                && t.getToStatus() == ScenarioStatus.INVALIDATED));
    }

    @Test
    void relabel_count_increments_on_leader_change() {
        manager.update("INFY", "1D", List.of(scoredScenario("S1", ScenarioStatus.LEADING)));
        HypothesisSnapshot snap = manager.update("INFY", "1D", List.of(scoredScenario("S2", ScenarioStatus.LEADING)));
        assertEquals(1, snap.getRelabelCount());
    }

    @Test
    void relabel_count_stable_when_same_leader() {
        manager.update("INFY", "1D", List.of(scoredScenario("S1", ScenarioStatus.LEADING)));
        HypothesisSnapshot snap = manager.update("INFY", "1D", List.of(scoredScenario("S1", ScenarioStatus.LEADING)));
        assertEquals(0, snap.getRelabelCount());
    }

    @Test
    void get_returns_null_before_first_update() {
        assertNull(manager.get("INFY", "1D"));
    }

    @Test
    void reset_clears_snapshot() {
        manager.update("INFY", "1D", List.of(scoredScenario("S1", ScenarioStatus.LEADING)));
        manager.reset("INFY", "1D");
        assertNull(manager.get("INFY", "1D"));
    }

    @Test
    void leading_scenario_id_set_correctly() {
        var scenarios = List.of(
                scoredScenario("S1", ScenarioStatus.LEADING),
                scoredScenario("S2", ScenarioStatus.ACTIVE_ALTERNATE)
        );
        HypothesisSnapshot snap = manager.update("INFY", "1D", scenarios);
        assertEquals("S1", snap.getLeadingScenarioId());
    }
}
