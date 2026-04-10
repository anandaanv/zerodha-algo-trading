package com.dtech.ta.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.service.copilot.CopilotOrchestratorService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ElliottWaveEngineLogicTest {

    @Test
    void scenarioBuilder_keepsBearishImpulseAsBearishContinuation() {
        ScenarioBuilder scenarioBuilder = new ScenarioBuilder();

        WaveCount bearishW4 = WaveCount.builder()
                .waveType(WaveCount.WaveType.IMPULSE)
                .primaryTimeframe("1h")
                .bullish(false)
                .currentWaveInProgress(WaveLabel.W4)
                .currentPositionDescription("Partial impulse down — bearish — W3 down complete")
                .pivots(List.of(
                        pivot(0, ZigZagPoint.Type.HIGH, 120),
                        pivot(1, ZigZagPoint.Type.LOW, 110),
                        pivot(2, ZigZagPoint.Type.HIGH, 116),
                        pivot(3, ZigZagPoint.Type.LOW, 100)
                ))
                .supportingEvidence(List.of())
                .contradictingEvidence(List.of())
                .ruleViolations(List.of())
                .build();

        List<WaveScenario> scenarios = scenarioBuilder.build(List.of(bearishW4), List.of(), "1h", 0.0);

        assertEquals(1, scenarios.size());
        assertEquals(WaveScenario.ScenarioDirection.BEARISH_CONTINUATION, scenarios.get(0).getDirection());
    }

    @Test
    void waveCounter_doesNotForceAmbiguousBullishRetraceIntoFlat() {
        WaveCounter waveCounter = new WaveCounter();

        List<EnrichedPivot> pivots = List.of(
                pivot(0, ZigZagPoint.Type.LOW, 100),
                pivot(1, ZigZagPoint.Type.HIGH, 120),
                pivot(2, ZigZagPoint.Type.LOW, 104)
        );

        List<WaveCount> counts = waveCounter.generateCounts(Map.of("1h", pivots), List.of("1h"));

        assertTrue(counts.stream().noneMatch(wc ->
                wc.isBullish()
                        && (wc.getWaveType() == WaveCount.WaveType.ZIGZAG
                        || wc.getWaveType() == WaveCount.WaveType.FLAT)));
    }

    @Test
    void nestedCorrectiveContext_isEmittedForHigherTimeframeTriangleBranching() {
        NestedCorrectiveContextBuilder builder = new NestedCorrectiveContextBuilder();

        TfContext dailyContext = TfContext.builder()
                .timeframe("1d")
                .currentPosition(WaveLabel.WE)
                .structureType(WaveCount.WaveType.TRIANGLE)
                .narrative("1d: terminal triangle leg inside higher-degree correction")
                .w4HardFloor(247.30)
                .build();

        PatternMatch triangle = PatternMatch.builder()
                .type(PatternType.SYMMETRICAL_TRIANGLE)
                .status(PatternStatus.WATCHING)
                .timeframe("1d")
                .description("Triangle compressing into terminal decision zone")
                .confidence(72)
                .waveContextHints(List.of(
                        WaveContextHint.builder()
                                .impliedCurrentPosition(WaveLabel.W4)
                                .impliedNextWave(WaveLabel.W5)
                                .probability(0.80)
                                .narrative("Triangle acting as Wave 4 continuation pattern")
                                .build(),
                        WaveContextHint.builder()
                                .impliedCurrentPosition(WaveLabel.WB)
                                .impliedNextWave(WaveLabel.WC)
                                .probability(0.55)
                                .narrative("Triangle acting as B-wave before C resumes")
                                .build()))
                .build();

        PatternMatch wedge = PatternMatch.builder()
                .type(PatternType.RISING_WEDGE)
                .status(PatternStatus.BUILDING)
                .timeframe("1d")
                .description("Terminal wedge risk near the upper boundary")
                .confidence(60)
                .build();

        List<NestedWaveContext> nested = builder.build(
                List.of("1d", "15m"),
                "15m",
                Map.of("1d", dailyContext),
                Map.of("1d", List.of(
                        pivot(0, ZigZagPoint.Type.HIGH, 322.00),
                        pivot(1, ZigZagPoint.Type.LOW, 281.80),
                        pivot(2, ZigZagPoint.Type.HIGH, 302.70),
                        pivot(3, ZigZagPoint.Type.LOW, 272.25)
                )),
                List.of(triangle, wedge), 0.0, List.of());

        assertEquals(1, nested.size());
        NestedWaveContext context = nested.get(0);
        assertEquals("W4", context.getHigherDegreeWave());
        assertEquals("B", context.getCorrectiveLeg());
        assertEquals("C-of-B", context.getActiveSubwave());
        assertEquals(2, context.getBranches().size());
        assertTrue(context.getBranches().stream().anyMatch(b -> "TRUNCATED_C_OF_B".equals(b.getCode())));
        assertTrue(context.getBranches().stream().anyMatch(b -> "EXTENDED_C_OF_B_TO_0.618".equals(b.getCode())));

        ElliottWaveAnalysis analysis = ElliottWaveAnalysis.builder()
                .primaryTimeframe("15m")
                .timeframes(List.of("1d", "15m"))
                .scenarios(List.of())
                .allPatterns(List.of())
                .enrichedPivots(Map.of())
                .waveCounts(List.of())
                .nestedCorrectiveContexts(nested)
                .build();

        String summary = analysis.toPromptSummary();
        assertTrue(summary.contains("W4 -> B -> C-of-B"));
        assertTrue(summary.contains("TRUNCATED_C_OF_B"));
        assertTrue(summary.contains("0.618"));
        assertTrue(summary.contains("4C"));
        assertFalse(summary.isBlank());
    }

    @Test
    void analysisSummary_includesNestedCorrectiveBranches() {
        ElliottWaveAnalysis analysis = ElliottWaveAnalysis.builder()
                .primaryTimeframe("5m")
                .timeframes(List.of("1w", "1d", "1h", "15m", "5m"))
                .scenarios(List.of())
                .allPatterns(List.of())
                .enrichedPivots(Map.of())
                .waveCounts(List.of())
                .nestedBranchNarrative("""
                        NESTED CORRECTIVE BRANCHES:
                          [1d] W4 -> B -> C
                            Current: Wave B is still the deceptive counter-trend phase
                            Thesis: Wave B remains a nested corrective branch inside the larger W4 structure
                            - Branch A: truncated terminal leg (conf=0.58): Failure before the projected extension completes can trigger the next decline immediately.
                            - Branch B: extension toward the 0.618 target (conf=0.66) target=301.25: If the move extends, it can complete the final corrective leg before the next reversal.
                        """)
                .build();

        String prompt = analysis.toPromptSummary();

        assertTrue(prompt.contains("NESTED CORRECTIVE BRANCHES"));
        assertTrue(prompt.contains("0.618"));
        assertTrue(prompt.contains("truncated terminal leg"));
    }

    @Test
    void reasoningPrompt_includesStoredElliottAndMarketStructureContext() {
        CopilotOrchestratorService orchestratorService = new CopilotOrchestratorService(
                null, null, null, null, null, null);

        CopilotInvestigation investigation = CopilotInvestigation.builder()
                .symbol("POWERGRID")
                .timeframesActive("1w,1d,1h,15m,5m")
                .elliottAnalysisData("=== ELLIOTT WAVE ENGINE ===\nNESTED CORRECTIVE BRANCHES:\n  [1d] W4 -> B -> C of B\n")
                .marketStructureData("Timeframe: 1d | Trend: DOWNTREND")
                .build();

        String prompt = orchestratorService.buildReasoningPrompt(
                "skill prompt",
                investigation,
                "observations summary",
                null,
                null,
                null);

        assertTrue(prompt.contains("PRE-COMPUTED ELLIOTT ANALYSIS"));
        assertTrue(prompt.contains("NESTED CORRECTIVE BRANCHES"));
        assertTrue(prompt.contains("MARKET STRUCTURE"));
        assertTrue(prompt.contains("DOWNTREND"));
    }

    private static EnrichedPivot pivot(int barIndex, ZigZagPoint.Type type, double price) {
        return EnrichedPivot.builder()
                .barIndex(barIndex)
                .timestamp(Instant.parse("2024-01-01T00:00:00Z").plusSeconds(barIndex * 3600L))
                .type(type)
                .price(price)
                .build();
    }
}
