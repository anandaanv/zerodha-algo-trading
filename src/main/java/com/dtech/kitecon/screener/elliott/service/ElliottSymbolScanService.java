package com.dtech.kitecon.screener.elliott.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.analysis.AnalysisProcessService;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.dto.FindingResponse;
import com.dtech.kitecon.service.copilot.dto.AIResponse;
import com.dtech.kitecon.screener.elliott.entity.*;
import com.dtech.kitecon.screener.elliott.repository.*;
import com.dtech.kitecon.service.copilot.AIResponseParser;
import com.dtech.kitecon.service.copilot.CopilotAIService;
import com.dtech.kitecon.service.copilot.CopilotOrchestratorService;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.ta.elliott.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.*;

/**
 * Runs the Elliott analysis for a single symbol in its own transaction (REQUIRES_NEW).
 * This ensures each symbol's result is committed immediately and independently.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ElliottSymbolScanService {

    private final ZigZagService zigzagService;
    private final MarketStructureService marketStructureService;
    private final AdvancedElliottService advancedElliottService;
    private final AnalysisProcessService analysisProcessService;
    private final CopilotOrchestratorService orchestratorService;
    private final CopilotAIService aiService;
    private final AIResponseParser responseParser;
    private final InstrumentRepository instrumentRepository;
    private final ObjectMapper objectMapper;
    private final SuggestionChartLayoutService layoutService;
    private final ElliottTradeSuggestionRepository suggestionRepository;
    private final ElliottScreenerRunResultRepository runResultRepository;

    /**
     * Scans a single symbol and saves a RunResult record in its own transaction.
     * Returns "PASSED" if a suggestion was created, "SKIPPED", "FAILED", or "ERROR".
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String scanSymbol(ElliottScreener screener, Long runId, Long userId, String symbol) {
        long startMs = System.currentTimeMillis();
        ElliottScreenerRunResult result = ElliottScreenerRunResult.builder()
                .runId(runId)
                .screenerId(screener.getId())
                .symbol(symbol)
                .status("ERROR") // default; updated on success
                .scannedAt(Instant.now())
                .build();

        try {
            String status = doScan(screener, runId, userId, symbol, result);
            result.setStatus(status);
            result.setProcessingMs(System.currentTimeMillis() - startMs);
            runResultRepository.save(result);
            return status;
        } catch (Exception e) {
            log.error("[ElliottScreener] Error scanning {} for screener {}: {}", symbol, screener.getId(), e.getMessage(), e);
            result.setStatus("ERROR");
            result.setErrorMessage(e.getMessage() != null ? e.getMessage().substring(0, Math.min(e.getMessage().length(), 500)) : "Unknown error");
            result.setProcessingMs(System.currentTimeMillis() - startMs);
            runResultRepository.save(result);
            return "ERROR";
        }
    }

    private String doScan(ElliottScreener screener, Long runId, Long userId, String symbol,
                           ElliottScreenerRunResult result) throws Exception {
        var instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) throw new IllegalArgumentException("Instrument not found: " + symbol);

        List<String> tfs = Arrays.stream(screener.getTimeframes().split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
        Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
        Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();
        List<String> orderedTfs = new ArrayList<>();
        StringBuilder msdSummary = new StringBuilder();

        for (String tf : tfs) {
            Interval interval = ElliottScreenerService.mapTimeframeToInterval(tf);
            if (interval == null) { log.warn("[ElliottScreener] Unknown tf {}", tf); continue; }
            try {
                List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(symbol, instrument, interval);
                BarSeries series = zigzagService.getBarSeries(symbol, instrument, interval);
                List<ZigZagPoint> pivotsWithLtp = zigzagService.withLtpPivot(pivots, series);
                MarketStructureData msd = marketStructureService.analyse(pivotsWithLtp, tf);
                msdSummary.append(msd.toPromptSummary()).append("\n");
                pivotsByTf.put(tf, pivotsWithLtp);
                structureByTf.put(tf, msd);
                seriesByTf.put(tf, series);
                orderedTfs.add(tf);
            } catch (Exception e) {
                log.warn("[ElliottScreener] TF {} failed for {}: {}", tf, symbol, e.getMessage());
            }
        }

        if (orderedTfs.isEmpty()) throw new IllegalStateException("No valid timeframes computed for " + symbol);

        String primaryTf = screener.getPrimaryTimeframe() != null
                ? screener.getPrimaryTimeframe()
                : orderedTfs.get(orderedTfs.size() - 1);

        AdvancedElliottAnalysisResult advResult = advancedElliottService.analyze(
                seriesByTf, pivotsByTf, structureByTf, orderedTfs, symbol, primaryTf);

        BarSeries primarySeries = seriesByTf.get(primaryTf);
        double currentPrice = (primarySeries != null && primarySeries.getBarCount() > 0)
                ? primarySeries.getLastBar().getClosePrice().doubleValue() : 0.0;

        var processed = analysisProcessService.process(
                advResult.getWaveAnalysis(), symbol, currentPrice, primaryTf);

        String data = "=== SYMBOL: " + symbol + " ===\n" + processed.getHumanReadable() + "\n" + msdSummary;
        String instructions = orchestratorService.buildScenarioEvaluatorInstructions();
        String rawResponse = aiService.call(userId, instructions, data);

        AIResponse aiResponse = responseParser.parse(rawResponse);
        if (!(aiResponse instanceof FindingResponse finding)) return "FAILED";
        if (!"ENTRY_READY".equals(finding.getCurrentStage())) return "FAILED";
        if (finding.getAnticipatoryEntry() == null) return "FAILED";

        String direction = deriveDirection(finding);
        if (direction == null) return "FAILED";

        boolean duplicate = suggestionRepository.existsByScreenerIdAndSymbolAndDirectionAndStateIn(
                screener.getId(), symbol, direction,
                List.of(SuggestionState.PROPOSED, SuggestionState.ANTICIPATORY, SuggestionState.ACTIVE));
        if (duplicate) return "SKIPPED";

        FindingResponse.TradeParameters ante = finding.getAnticipatoryEntry();
        ElliottTradeSuggestion suggestion = ElliottTradeSuggestion.builder()
                .screenerId(screener.getId())
                .runId(runId)
                .userId(userId)
                .symbol(symbol)
                .direction(direction)
                .state(SuggestionState.PROPOSED)
                .hypothesisLabel(finding.getHypothesisLabel())
                .waveContext(finding.getWaveContext())
                .pattern(finding.getPattern())
                .currentStage(finding.getCurrentStage())
                .entryZone(ante.getEntryZone())
                .stopLoss(ante.getSl())
                .target1(ante.getTp())
                .triggerDescription(ante.getTriggerDescription())
                .reasoning(finding.getReasoning() != null ? finding.getReasoning().toString() : null)
                .confidenceLayersJson(objectMapper.writeValueAsString(finding.getConfidenceLayers()))
                .invalidationConditionsJson(objectMapper.writeValueAsString(finding.getInvalidationConditions()))
                .anomalyFlagsJson(objectMapper.writeValueAsString(finding.getAnomalyFlags()))
                .rawAiResponse(rawResponse)
                .primaryTimeframe(primaryTf)
                .allTimeframes(String.join(",", orderedTfs))
                .build();
        suggestion = suggestionRepository.save(suggestion);
        result.setSuggestionId(suggestion.getId());

        try {
            Map<String, ElliottWaveAnalysis> analysisByTf = new LinkedHashMap<>();
            for (String tf : orderedTfs) analysisByTf.put(tf, advResult.getWaveAnalysis());
            layoutService.generateAndSaveLayouts(suggestion, analysisByTf, seriesByTf);
        } catch (Exception e) {
            log.warn("Failed to generate chart layouts for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
        }

        return "PASSED";
    }

    private String deriveDirection(FindingResponse finding) {
        String text = "";
        if (finding.getAnticipatoryEntry() != null && finding.getAnticipatoryEntry().getTriggerDescription() != null)
            text += " " + finding.getAnticipatoryEntry().getTriggerDescription();
        if (finding.getWaveContext() != null) text += " " + finding.getWaveContext();
        if (finding.getHypothesisLabel() != null) text += " " + finding.getHypothesisLabel();
        String lower = text.toLowerCase();
        if (lower.contains("long") || lower.contains("buy") || lower.contains("bullish") || lower.contains("bull")) return "LONG";
        if (lower.contains("short") || lower.contains("sell") || lower.contains("bearish") || lower.contains("bear")) return "SHORT";
        return null;
    }
}
