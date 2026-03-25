package com.dtech.ta.elliott;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.ta.elliott.confluence.*;
import com.dtech.ta.elliott.decomposition.MultiDegreePivotBuilder;
import com.dtech.ta.elliott.decomposition.MultiDegreePivotSet;
import com.dtech.ta.elliott.decomposition.PivotDegree;
import com.dtech.ta.elliott.hypothesis.HypothesisManager;
import com.dtech.ta.elliott.hypothesis.HypothesisSnapshot;
import com.dtech.ta.elliott.scenario.ScoredScenario;
import com.dtech.ta.elliott.scenario.ScenarioStatusTracker;
import com.dtech.ta.elliott.swing.Swing;
import com.dtech.ta.elliott.swing.SwingBuilder;
import com.dtech.ta.elliott.trigger.CandleTrigger;
import com.dtech.ta.elliott.trigger.CandleTriggerDetector;
import com.dtech.ta.elliott.trigger.EntryBuilder;
import com.dtech.ta.elliott.trigger.EntryCandidate;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdvancedElliottService {

    private final ElliottWaveAnalyzer elliottWaveAnalyzer;
    private final SwingBuilder swingBuilder;
    private final MultiDegreePivotBuilder multiDegreePivotBuilder;
    private final FibLevelBuilder fibLevelBuilder;
    private final HorizontalSRBuilder horizontalSRBuilder;
    private final ConfluenceAggregator confluenceAggregator;
    private final DecisionZoneClassifier decisionZoneClassifier;
    private final ScenarioStatusTracker scenarioStatusTracker;
    private final CandleTriggerDetector candleTriggerDetector;
    private final EntryBuilder entryBuilder;
    private final HypothesisManager hypothesisManager;

    /**
     * Run the full advanced Elliott analysis pipeline.
     *
     * @param barSeriesByTf   map of timeframe → BarSeries
     * @param zigzagByTf      map of timeframe → List of ZigZagPoints
     * @param structureByTf   map of timeframe → MarketStructureData
     * @param tfOrder         ordered list of timeframes (highest degree first)
     * @param symbol          instrument symbol
     * @param primaryTf       primary timeframe for hypothesis manager
     * @return AdvancedElliottAnalysisResult
     */
    public AdvancedElliottAnalysisResult analyze(
            Map<String, BarSeries> barSeriesByTf,
            Map<String, List<ZigZagPoint>> zigzagByTf,
            Map<String, MarketStructureData> structureByTf,
            List<String> tfOrder,
            String symbol,
            String primaryTf) {

        // Step 1: Run existing Elliott Wave engine (Layers 4-8)
        ElliottWaveAnalysis waveAnalysis = null;
        try {
            waveAnalysis = elliottWaveAnalyzer.analyze(zigzagByTf, structureByTf, barSeriesByTf, tfOrder, primaryTf);
        } catch (Exception e) {
            log.warn("[AdvancedElliott] Elliott wave analysis failed: {}", e.getMessage());
        }

        // Step 2: Build confluence zones from the primary TF
        List<ConfluenceZone> confluenceZones = new ArrayList<>();
        BarSeries primarySeries = barSeriesByTf.get(primaryTf);
        List<ZigZagPoint> primaryPivots = zigzagByTf.get(primaryTf);

        if (primarySeries != null && primaryPivots != null && !primaryPivots.isEmpty()) {
            try {
                // Multi-degree pivots
                MultiDegreePivotSet degreeSet = multiDegreePivotBuilder.build(primarySeries, symbol, primaryTf);
                List<ZigZagPoint> mediumPivots = degreeSet.getMediumPivots();

                // Build swings from medium pivots
                List<Swing> swings = swingBuilder.build(mediumPivots, symbol, primaryTf);

                List<PriceLevel> allLevels = new ArrayList<>();

                // Fib levels from the most recent completed swing (second to last)
                if (swings.size() >= 2) {
                    Swing baseSwing = swings.get(swings.size() - 2);
                    List<PriceLevel> retrace = fibLevelBuilder.buildRetracementLevels(baseSwing, 0.005);
                    double originPrice = swings.get(swings.size() - 1).getEndPrice();
                    List<PriceLevel> extend = fibLevelBuilder.buildExtensionLevels(baseSwing, originPrice, 0.005);
                    allLevels.addAll(retrace);
                    allLevels.addAll(extend);
                }

                // Horizontal S/R from medium pivots
                List<PriceLevel> srLevels = horizontalSRBuilder.buildSRLevels(mediumPivots, 0.01);
                allLevels.addAll(srLevels);

                // Aggregate and classify
                List<ConfluenceZone> rawZones = confluenceAggregator.aggregate(allLevels);
                List<WaveScenario> scenarios = waveAnalysis != null ? waveAnalysis.getScenarios() : List.of();
                confluenceZones = decisionZoneClassifier.classify(rawZones, scenarios, 4.0);

            } catch (Exception e) {
                log.warn("[AdvancedElliott] Confluence zone build failed: {}", e.getMessage());
            }
        }

        // Step 3: Score scenarios
        List<ScoredScenario> scoredScenarios = new ArrayList<>();
        if (waveAnalysis != null && waveAnalysis.getScenarios() != null) {
            double currentPrice = 0.0;
            if (primarySeries != null && !primarySeries.isEmpty()) {
                currentPrice = primarySeries.getBar(primarySeries.getEndIndex()).getClosePrice().doubleValue();
            }
            try {
                scoredScenarios = scenarioStatusTracker.evaluate(waveAnalysis.getScenarios(), currentPrice);
            } catch (Exception e) {
                log.warn("[AdvancedElliott] Scenario scoring failed: {}", e.getMessage());
            }
        }

        // Step 4: Detect candle triggers
        List<CandleTrigger> triggers = new ArrayList<>();
        if (primarySeries != null) {
            try {
                triggers = candleTriggerDetector.detect(primarySeries, 5);
            } catch (Exception e) {
                log.warn("[AdvancedElliott] Trigger detection failed: {}", e.getMessage());
            }
        }

        // Step 5: Build entry candidates
        List<EntryCandidate> entryCandidates = new ArrayList<>();
        double currentPrice = 0.0;
        if (primarySeries != null && !primarySeries.isEmpty()) {
            currentPrice = primarySeries.getBar(primarySeries.getEndIndex()).getClosePrice().doubleValue();
        }
        try {
            entryCandidates = entryBuilder.build(scoredScenarios, triggers, confluenceZones, currentPrice);
        } catch (Exception e) {
            log.warn("[AdvancedElliott] Entry building failed: {}", e.getMessage());
        }

        // Step 6: Update hypothesis manager
        HypothesisSnapshot snapshot = null;
        try {
            snapshot = hypothesisManager.update(symbol, primaryTf, scoredScenarios);
        } catch (Exception e) {
            log.warn("[AdvancedElliott] Hypothesis manager update failed: {}", e.getMessage());
        }

        // Step 7: Build prompt summary
        String promptSummary = buildPromptSummary(waveAnalysis, confluenceZones, entryCandidates);

        return AdvancedElliottAnalysisResult.builder()
                .waveAnalysis(waveAnalysis)
                .confluenceZones(confluenceZones)
                .scoredScenarios(scoredScenarios)
                .entryCandidates(entryCandidates)
                .hypothesisSnapshot(snapshot)
                .promptSummary(promptSummary)
                .build();
    }

    private String buildPromptSummary(ElliottWaveAnalysis waveAnalysis,
                                       List<ConfluenceZone> zones,
                                       List<EntryCandidate> entries) {
        StringBuilder sb = new StringBuilder();
        if (waveAnalysis != null) {
            sb.append(waveAnalysis.toPromptSummary()).append("\n\n");
        }
        if (!zones.isEmpty()) {
            sb.append("=== Confluence Zones ===\n");
            zones.stream().limit(5).forEach(z ->
                sb.append(String.format("  [%s] %.2f-%.2f score=%.1f type=%s\n",
                    z.getId(), z.getLowerPrice(), z.getUpperPrice(), z.getScore(), z.getZoneType())));
            sb.append("\n");
        }
        if (!entries.isEmpty()) {
            sb.append("=== Entry Candidates ===\n");
            entries.forEach(e ->
                sb.append(String.format("  [%s] %s entry=%.2f sl=%.2f t1=%.2f rr=%.2f\n",
                    e.getId(), e.isBullish() ? "LONG" : "SHORT",
                    e.getEntryPrice(), e.getStopLoss(), e.getTarget1(), e.getRiskRewardRatio())));
        }
        return sb.toString();
    }
}
