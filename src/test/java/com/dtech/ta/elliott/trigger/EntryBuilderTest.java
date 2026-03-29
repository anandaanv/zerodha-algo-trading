package com.dtech.ta.elliott.trigger;

import com.dtech.ta.elliott.WaveHypothesis;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.confluence.ConfluenceZone;
import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntryBuilderTest {

    private EntryBuilder entryBuilder;

    @BeforeEach
    void setUp() {
        entryBuilder = new EntryBuilder();
    }

    private ScoredScenario leadingBullishScenario(double invalidation, double target) {
        WaveHypothesis.FibTarget ft = WaveHypothesis.FibTarget.builder()
                .level(target).waveName("W5").confidence(0.8).build();
        WaveHypothesis hyp = WaveHypothesis.builder()
                .id("H1")
                .totalScore(25)
                .invalidationLevel(invalidation)
                .nextMajorMoveUp(true)
                .primaryTarget(ft)
                .targetZones(new ArrayList<>())
                .build();
        WaveScenario scenario = WaveScenario.builder()
                .id("S1")
                .direction(WaveScenario.ScenarioDirection.BULLISH_CONTINUATION)
                .hypotheses(List.of(hyp))
                .build();
        return ScoredScenario.builder()
                .scenario(scenario)
                .status(ScenarioStatus.LEADING)
                .totalScore(25)
                .statusReasons(new ArrayList<>())
                .build();
    }

    private ConfluenceZone decisionZone(double lower, double upper) {
        return ConfluenceZone.builder()
                .id("CZ_1")
                .lowerPrice(lower)
                .upperPrice(upper)
                .midPrice((lower + upper) / 2)
                .score(6.0)
                .zoneType("DECISION")
                .explanation(new ArrayList<>())
                .build();
    }

    private CandleTrigger hammerTrigger(double price) {
        return CandleTrigger.builder()
                .type(TriggerType.HAMMER)
                .barIndex(10)
                .triggerPrice(price)
                .bullish(true)
                .reasons(List.of("hammer"))
                .build();
    }

    @Test
    void entry_created_when_trigger_in_decision_zone() {
        ScoredScenario ss = leadingBullishScenario(90.0, 130.0);
        ConfluenceZone zone = decisionZone(100.0, 102.0);
        CandleTrigger trigger = hammerTrigger(101.0);

        var result = entryBuilder.build(List.of(ss), List.of(trigger), List.of(zone), 101.0);
        assertEquals(1, result.size());
        assertEquals("AGGRESSIVE", result.get(0).getEntryStyle());
        assertEquals(90.0, result.get(0).getStopLoss(), 0.001);
        assertEquals(130.0, result.get(0).getTarget1(), 0.001);
    }

    @Test
    void no_entry_when_trigger_wrong_direction() {
        ScoredScenario ss = leadingBullishScenario(90.0, 130.0);
        ConfluenceZone zone = decisionZone(100.0, 102.0);
        CandleTrigger bearishTrigger = CandleTrigger.builder()
                .type(TriggerType.SHOOTING_STAR).barIndex(10)
                .triggerPrice(101.0).bullish(false).reasons(List.of()).build();

        var result = entryBuilder.build(List.of(ss), List.of(bearishTrigger), List.of(zone), 101.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void no_entry_when_not_in_decision_zone() {
        ScoredScenario ss = leadingBullishScenario(90.0, 130.0);
        ConfluenceZone zone = decisionZone(100.0, 102.0);
        CandleTrigger trigger = hammerTrigger(200.0);

        var result = entryBuilder.build(List.of(ss), List.of(trigger), List.of(zone), 200.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void no_entry_for_weak_alternate_scenario() {
        ScoredScenario ss = leadingBullishScenario(90.0, 130.0);
        ss.setStatus(ScenarioStatus.WEAK_ALTERNATE);
        ConfluenceZone zone = decisionZone(100.0, 102.0);
        CandleTrigger trigger = hammerTrigger(101.0);

        var result = entryBuilder.build(List.of(ss), List.of(trigger), List.of(zone), 101.0);
        assertTrue(result.isEmpty());
    }

    @Test
    void empty_triggers_returns_empty() {
        ScoredScenario ss = leadingBullishScenario(90.0, 130.0);
        var result = entryBuilder.build(List.of(ss), List.of(), List.of(), 101.0);
        assertTrue(result.isEmpty());
    }
}
