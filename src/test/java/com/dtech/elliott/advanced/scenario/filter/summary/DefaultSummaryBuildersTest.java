package com.dtech.elliott.advanced.scenario.filter.summary;

import com.dtech.elliott.advanced.common.enums.Direction;
import com.dtech.elliott.advanced.scenario.filter.domain.*;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DefaultSummaryBuildersTest {

    private DefaultHumanResearchSummaryBuilder humanBuilder;
    private DefaultReasoningPayloadCompressionBuilder payloadBuilder;

    @BeforeEach
    void setUp() {
        humanBuilder = new DefaultHumanResearchSummaryBuilder();
        payloadBuilder = new DefaultReasoningPayloadCompressionBuilder();
    }

    private ScenarioConflictSet emptyConflict(String mode) {
        return new ScenarioConflictSet("NIFTY", "15m", List.of(), List.of(), List.of(), mode, List.of(mode));
    }

    private ScenarioFamilyCandidate family(String id, ScenarioFamilyType type, Direction dir, boolean triggerEligible) {
        FamilyScore score = new FamilyScore(0.7, 0.6, 0.5, 0.1, 0.1, 0.7, 0.5, 0.65);
        return new ScenarioFamilyCandidate(
                id, "NIFTY", "15m", type, dir,
                List.of(), List.of(), List.of("FALLING_WEDGE"), List.of(),
                1800.0, 0.0, 0.0, false, triggerEligible, false,
                ScenarioStatus.LEADING, score, List.of(), List.of(), Map.of()
        );
    }

    private FilteredScenarioSet set(ScenarioFamilyCandidate leading, List<ScenarioFamilyCandidate> alternates) {
        return new FilteredScenarioSet("NIFTY", "15m", leading, alternates,
                List.of(), List.of(), emptyConflict("BULLISH_VS_BEARISH"), null, null);
    }

    @Test
    void human_summary_includes_market_state() {
        var leading = family("F1", ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, Direction.UP, true);
        HumanResearchSummary summary = humanBuilder.build(set(leading, List.of()));
        assertNotNull(summary.marketStateSummary());
        assertFalse(summary.marketStateSummary().isBlank());
    }

    @Test
    void human_summary_has_leading_scenario_bullets() {
        var leading = family("F1", ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, Direction.UP, true);
        HumanResearchSummary summary = humanBuilder.build(set(leading, List.of()));
        assertFalse(summary.leadingScenarioSummary().isEmpty());
    }

    @Test
    void human_summary_handles_null_leading() {
        FilteredScenarioSet emptySet = new FilteredScenarioSet("NIFTY", "15m",
                null, List.of(), List.of(), List.of(),
                emptyConflict("LOW_CONVICTION_NOISE"), null, null);
        HumanResearchSummary summary = humanBuilder.build(emptySet);
        assertTrue(summary.marketStateSummary().toLowerCase().contains("no clear leading"));
    }

    @Test
    void reasoning_payload_is_compact() {
        var leading = family("F1", ScenarioFamilyType.BULLISH_CONTINUATION_AFTER_CORRECTION, Direction.UP, true);
        var alt1 = family("F2", ScenarioFamilyType.SIDEWAYS_COMPRESSION_BEFORE_BREAKOUT, Direction.NEUTRAL, false);
        var alt2 = family("F3", ScenarioFamilyType.BEARISH_REVERSAL_AFTER_TERMINAL_PUSH, Direction.DOWN, false);
        FilteredScenarioSet s = set(leading, List.of(alt1, alt2));
        ReasoningPayloadCompression payload = payloadBuilder.build(s);
        assertNotNull(payload.leadingScenario());
        assertEquals(2, payload.alternates().size());
    }
}
