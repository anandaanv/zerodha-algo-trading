package com.dtech.ta.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import com.dtech.ta.elliott.confluence.*;
import com.dtech.ta.elliott.decomposition.MultiDegreePivotBuilder;
import com.dtech.ta.elliott.decomposition.MultiDegreePivotSet;
import com.dtech.ta.elliott.decomposition.PivotDegree;
import com.dtech.ta.elliott.hypothesis.HypothesisManager;
import com.dtech.ta.elliott.hypothesis.HypothesisSnapshot;
import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatus;
import com.dtech.ta.elliott.scenario.ScenarioStatusTracker;
import com.dtech.ta.elliott.swing.SwingBuilder;
import com.dtech.ta.elliott.trigger.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.ta4j.core.BarSeries;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

class AdvancedElliottServiceTest {

    private ElliottWaveAnalyzer elliottWaveAnalyzer;
    private SwingBuilder swingBuilder;
    private MultiDegreePivotBuilder multiDegreePivotBuilder;
    private FibLevelBuilder fibLevelBuilder;
    private HorizontalSRBuilder horizontalSRBuilder;
    private ConfluenceAggregator confluenceAggregator;
    private DecisionZoneClassifier decisionZoneClassifier;
    private ScenarioStatusTracker scenarioStatusTracker;
    private CandleTriggerDetector candleTriggerDetector;
    private EntryBuilder entryBuilder;
    private HypothesisManager hypothesisManager;

    private AdvancedElliottService service;

    @BeforeEach
    void setUp() {
        elliottWaveAnalyzer     = Mockito.mock(ElliottWaveAnalyzer.class);
        swingBuilder            = Mockito.mock(SwingBuilder.class);
        multiDegreePivotBuilder = Mockito.mock(MultiDegreePivotBuilder.class);
        fibLevelBuilder         = Mockito.mock(FibLevelBuilder.class);
        horizontalSRBuilder     = Mockito.mock(HorizontalSRBuilder.class);
        confluenceAggregator    = Mockito.mock(ConfluenceAggregator.class);
        decisionZoneClassifier  = Mockito.mock(DecisionZoneClassifier.class);
        scenarioStatusTracker   = Mockito.mock(ScenarioStatusTracker.class);
        candleTriggerDetector   = Mockito.mock(CandleTriggerDetector.class);
        entryBuilder            = Mockito.mock(EntryBuilder.class);
        hypothesisManager       = Mockito.mock(HypothesisManager.class);

        service = new AdvancedElliottService(
                elliottWaveAnalyzer, swingBuilder, multiDegreePivotBuilder,
                fibLevelBuilder, horizontalSRBuilder, confluenceAggregator,
                decisionZoneClassifier, scenarioStatusTracker, candleTriggerDetector,
                entryBuilder, hypothesisManager);
    }

    private BarSeries twoBarSeries() {
        BarSeries s = new org.ta4j.core.BaseBarSeriesBuilder().withName("test").build();
        s.addBar(com.dtech.kitecon.strategy.dataloader.BarsLoader.getBar(
                100, 110, 90, 105, 1000, java.time.Instant.now().minusSeconds(86400)));
        s.addBar(com.dtech.kitecon.strategy.dataloader.BarsLoader.getBar(
                105, 115, 100, 108, 1200, java.time.Instant.now()));
        return s;
    }

    @Test
    void result_not_null_when_all_services_return_empty() {
        BarSeries series = twoBarSeries();
        Map<String, BarSeries> barMap = Map.of("1D", series);
        Map<String, List<ZigZagPoint>> pivotMap = Map.of("1D", List.of());
        Map<String, MarketStructureData> structMap = Map.of("1D", MarketStructureData.builder().timeframe("1D").build());

        ElliottWaveAnalysis mockAnalysis = Mockito.mock(ElliottWaveAnalysis.class);
        when(mockAnalysis.getScenarios()).thenReturn(List.of());
        when(mockAnalysis.toPromptSummary()).thenReturn("summary");
        when(elliottWaveAnalyzer.analyze(any(), any(), any(), any(), any())).thenReturn(mockAnalysis);

        MultiDegreePivotSet mockSet = Mockito.mock(MultiDegreePivotSet.class);
        when(mockSet.getMediumPivots()).thenReturn(List.of());
        when(multiDegreePivotBuilder.build(any(), any(), any())).thenReturn(mockSet);

        when(swingBuilder.build(any(), any(), any())).thenReturn(List.of());
        when(horizontalSRBuilder.buildSRLevels(any(), anyDouble())).thenReturn(List.of());
        when(confluenceAggregator.aggregate(any())).thenReturn(List.of());
        when(decisionZoneClassifier.classify(any(), any(), anyDouble())).thenReturn(List.of());
        when(scenarioStatusTracker.evaluate(any(), anyDouble())).thenReturn(List.of());
        when(candleTriggerDetector.detect(any(), anyInt())).thenReturn(List.of());
        when(entryBuilder.build(any(), any(), any(), anyDouble())).thenReturn(List.of());

        HypothesisSnapshot snap = HypothesisSnapshot.builder()
                .symbol("INFY").primaryTimeframe("1D").snapshotTime(System.currentTimeMillis())
                .scoredScenarios(List.of()).transitions(List.of()).relabelCount(0).build();
        when(hypothesisManager.update(any(), any(), any())).thenReturn(snap);

        AdvancedElliottAnalysisResult result = service.analyze(
                barMap, pivotMap, structMap, List.of("1D"), "INFY", "1D");

        assertNotNull(result);
        assertNotNull(result.getWaveAnalysis());
        assertNotNull(result.getConfluenceZones());
        assertNotNull(result.getScoredScenarios());
        assertNotNull(result.getEntryCandidates());
        assertNotNull(result.getHypothesisSnapshot());
        assertNotNull(result.getPromptSummary());
    }

    @Test
    void result_non_null_when_wave_analysis_throws() {
        BarSeries series = twoBarSeries();
        when(elliottWaveAnalyzer.analyze(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("analysis failed"));

        MultiDegreePivotSet mockSet = Mockito.mock(MultiDegreePivotSet.class);
        when(mockSet.getMediumPivots()).thenReturn(List.of());
        when(multiDegreePivotBuilder.build(any(), any(), any())).thenReturn(mockSet);
        when(swingBuilder.build(any(), any(), any())).thenReturn(List.of());
        when(horizontalSRBuilder.buildSRLevels(any(), anyDouble())).thenReturn(List.of());
        when(confluenceAggregator.aggregate(any())).thenReturn(List.of());
        when(decisionZoneClassifier.classify(any(), any(), anyDouble())).thenReturn(List.of());
        when(scenarioStatusTracker.evaluate(any(), anyDouble())).thenReturn(List.of());
        when(candleTriggerDetector.detect(any(), anyInt())).thenReturn(List.of());
        when(entryBuilder.build(any(), any(), any(), anyDouble())).thenReturn(List.of());
        when(hypothesisManager.update(any(), any(), any())).thenReturn(
                HypothesisSnapshot.builder().symbol("X").primaryTimeframe("1D")
                        .snapshotTime(0).scoredScenarios(List.of())
                        .transitions(List.of()).relabelCount(0).build());

        AdvancedElliottAnalysisResult result = service.analyze(
                Map.of("1D", series), Map.of("1D", List.of()),
                Map.of("1D", MarketStructureData.builder().timeframe("1D").build()),
                List.of("1D"), "X", "1D");

        assertNotNull(result);
        assertNull(result.getWaveAnalysis()); // wave analysis failed, should be null
    }

    @Test
    void scored_scenarios_passed_to_hypothesis_manager() {
        BarSeries series = twoBarSeries();
        ElliottWaveAnalysis mockAnalysis = Mockito.mock(ElliottWaveAnalysis.class);
        when(mockAnalysis.getScenarios()).thenReturn(List.of());
        when(mockAnalysis.toPromptSummary()).thenReturn("");
        when(elliottWaveAnalyzer.analyze(any(), any(), any(), any(), any())).thenReturn(mockAnalysis);

        MultiDegreePivotSet mockSet = Mockito.mock(MultiDegreePivotSet.class);
        when(mockSet.getMediumPivots()).thenReturn(List.of());
        when(multiDegreePivotBuilder.build(any(), any(), any())).thenReturn(mockSet);
        when(swingBuilder.build(any(), any(), any())).thenReturn(List.of());
        when(horizontalSRBuilder.buildSRLevels(any(), anyDouble())).thenReturn(List.of());
        when(confluenceAggregator.aggregate(any())).thenReturn(List.of());
        when(decisionZoneClassifier.classify(any(), any(), anyDouble())).thenReturn(List.of());

        ScoredScenario ss = ScoredScenario.builder().status(ScenarioStatus.LEADING)
                .totalScore(25).statusReasons(List.of()).build();
        when(scenarioStatusTracker.evaluate(any(), anyDouble())).thenReturn(List.of(ss));
        when(candleTriggerDetector.detect(any(), anyInt())).thenReturn(List.of());
        when(entryBuilder.build(any(), any(), any(), anyDouble())).thenReturn(List.of());

        HypothesisSnapshot snap = HypothesisSnapshot.builder()
                .symbol("INFY").primaryTimeframe("1D").relabelCount(0)
                .scoredScenarios(List.of(ss)).transitions(List.of())
                .snapshotTime(System.currentTimeMillis()).build();
        when(hypothesisManager.update(eq("INFY"), eq("1D"), anyList())).thenReturn(snap);

        AdvancedElliottAnalysisResult result = service.analyze(
                Map.of("1D", series), Map.of("1D", List.of()),
                Map.of("1D", MarketStructureData.builder().timeframe("1D").build()),
                List.of("1D"), "INFY", "1D");

        assertEquals(1, result.getScoredScenarios().size());
        assertNotNull(result.getHypothesisSnapshot());
    }
}
