package com.dtech.ta.elliott.confluence;

import com.dtech.ta.elliott.WaveHypothesis;
import com.dtech.ta.elliott.WaveScenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DecisionZoneClassifierTest {

    private DecisionZoneClassifier classifier;

    @BeforeEach
    void setUp() {
        classifier = new DecisionZoneClassifier();
    }

    private ConfluenceZone zone(double mid, double score) {
        return ConfluenceZone.builder()
                .id("CZ_1")
                .midPrice(mid)
                .lowerPrice(mid - 1)
                .upperPrice(mid + 1)
                .score(score)
                .zoneType("MIXED")
                .explanation(new ArrayList<>())
                .build();
    }

    private WaveScenario scenarioWith(WaveHypothesis... hypotheses) {
        return WaveScenario.builder()
                .id("S1")
                .hypotheses(List.of(hypotheses))
                .build();
    }

    private WaveHypothesis hypothesis(String id, double invalidation, double targetLevel) {
        WaveHypothesis.FibTarget target = targetLevel > 0
                ? WaveHypothesis.FibTarget.builder().level(targetLevel).waveName("W5").confidence(0.7).build()
                : null;
        return WaveHypothesis.builder()
                .id(id)
                .invalidationLevel(invalidation)
                .primaryTarget(target)
                .targetZones(new ArrayList<>())
                .build();
    }

    @Test
    void zone_promoted_when_near_invalidation() {
        ConfluenceZone z = zone(100.0, 6.0);
        WaveHypothesis h = hypothesis("H1", 101.0, 0); // invalidation at 101, within 3% of 100
        var result = classifier.classify(List.of(z), List.of(scenarioWith(h)), 4.0);
        assertEquals("DECISION", result.get(0).getZoneType());
        assertTrue(result.get(0).getExplanation().stream().anyMatch(e -> e.contains("near_invalidation")));
    }

    @Test
    void zone_promoted_when_near_target() {
        ConfluenceZone z = zone(100.0, 6.0);
        WaveHypothesis h = hypothesis("H1", 50.0, 101.0); // target at 101, within 2% of 100
        var result = classifier.classify(List.of(z), List.of(scenarioWith(h)), 4.0);
        assertEquals("DECISION", result.get(0).getZoneType());
        assertTrue(result.get(0).getExplanation().stream().anyMatch(e -> e.contains("near_target")));
    }

    @Test
    void zone_not_promoted_when_score_too_low() {
        ConfluenceZone z = zone(100.0, 2.0); // score=2, minScore=4
        WaveHypothesis h = hypothesis("H1", 101.0, 0);
        var result = classifier.classify(List.of(z), List.of(scenarioWith(h)), 4.0);
        assertNotEquals("DECISION", result.get(0).getZoneType());
    }

    @Test
    void zone_not_promoted_when_no_scenario_alignment() {
        ConfluenceZone z = zone(100.0, 8.0);
        WaveHypothesis h = hypothesis("H1", 200.0, 300.0); // far from 100
        var result = classifier.classify(List.of(z), List.of(scenarioWith(h)), 4.0);
        assertNotEquals("DECISION", result.get(0).getZoneType());
    }

    @Test
    void empty_zones_returns_empty() {
        var result = classifier.classify(List.of(), List.of(), 4.0);
        assertTrue(result.isEmpty());
    }
}
