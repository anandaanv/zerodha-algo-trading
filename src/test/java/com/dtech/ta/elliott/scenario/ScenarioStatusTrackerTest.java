package com.dtech.ta.elliott.scenario;

import com.dtech.ta.elliott.WaveHypothesis;
import com.dtech.ta.elliott.WaveScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScenarioStatusTrackerTest {

    private ScenarioStatusTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ScenarioStatusTracker();
    }

    private WaveHypothesis hyp(int score) {
        return WaveHypothesis.builder().id("H1").totalScore(score).invalidationLevel(0).build();
    }

    private WaveScenario scenario(String id, WaveScenario.ScenarioDirection dir,
                                   double invalidation, int... hypScores) {
        List<WaveHypothesis> hypotheses = new java.util.ArrayList<>();
        for (int s : hypScores) hypotheses.add(hyp(s));
        return WaveScenario.builder()
                .id(id)
                .direction(dir)
                .scenarioInvalidation(invalidation)
                .hypotheses(hypotheses)
                .build();
    }

    @Test
    void highest_score_is_leading() {
        var s1 = scenario("S1", WaveScenario.ScenarioDirection.BULLISH_CONTINUATION, 0, 15, 15); // total=30
        var s2 = scenario("S2", WaveScenario.ScenarioDirection.BEARISH_CONTINUATION, 0, 12, 8);  // total=20
        var s3 = scenario("S3", WaveScenario.ScenarioDirection.RANGE_RESOLUTION, 0, 3, 2);       // total=5
        var result = tracker.evaluate(List.of(s1, s2, s3), 100.0);

        assertEquals(ScenarioStatus.LEADING, result.get(0).getStatus());
        assertEquals(ScenarioStatus.ACTIVE_ALTERNATE, result.get(1).getStatus());
        assertEquals(ScenarioStatus.WEAK_ALTERNATE, result.get(2).getStatus());
    }

    @Test
    void bullish_scenario_invalidated_when_price_below_invalidation() {
        // Bullish scenario with invalidation at 100 — price 90 is below
        var s = scenario("S1", WaveScenario.ScenarioDirection.BULLISH_CONTINUATION, 100.0, 20);
        var result = tracker.evaluate(List.of(s), 90.0);
        assertEquals(ScenarioStatus.INVALIDATED, result.get(0).getStatus());
    }

    @Test
    void bearish_scenario_invalidated_when_price_above_invalidation() {
        // Bearish scenario with invalidation at 200 — price 210 is above
        var s = scenario("S1", WaveScenario.ScenarioDirection.BEARISH_CONTINUATION, 200.0, 20);
        var result = tracker.evaluate(List.of(s), 210);
        assertEquals(ScenarioStatus.INVALIDATED, result.get(0).getStatus());
    }

    @Test
    void all_below_weak_floor_get_awaiting_trigger() {
        var s1 = scenario("S1", WaveScenario.ScenarioDirection.RANGE_RESOLUTION, 0, 1);
        var s2 = scenario("S2", WaveScenario.ScenarioDirection.RANGE_RESOLUTION, 0, 2);
        var result = tracker.evaluate(List.of(s1, s2), 100.0);
        result.forEach(ss -> assertEquals(ScenarioStatus.AWAITING_TRIGGER, ss.getStatus()));
    }

    @Test
    void empty_input_returns_empty() {
        assertTrue(tracker.evaluate(List.of(), 100.0).isEmpty());
    }

    @Test
    void null_input_returns_empty() {
        assertTrue(tracker.evaluate(null, 100.0).isEmpty());
    }

    @Test
    void only_one_leading_scenario() {
        var s1 = scenario("S1", WaveScenario.ScenarioDirection.BULLISH_CONTINUATION, 0, 15, 15);
        var s2 = scenario("S2", WaveScenario.ScenarioDirection.BEARISH_CONTINUATION, 0, 12, 8);
        var s3 = scenario("S3", WaveScenario.ScenarioDirection.RANGE_RESOLUTION, 0, 10, 10);
        var result = tracker.evaluate(List.of(s1, s2, s3), 100.0);
        long leadingCount = result.stream()
                .filter(ss -> ss.getStatus() == ScenarioStatus.LEADING)
                .count();
        assertEquals(1, leadingCount);
    }
}
