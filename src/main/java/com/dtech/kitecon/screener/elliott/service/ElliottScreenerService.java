package com.dtech.kitecon.screener.elliott.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.analysis.AnalysisProcessService;
import com.dtech.kitecon.analysis.dto.ProcessAnalysisResponse;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.screener.elliott.dto.*;
import com.dtech.kitecon.screener.elliott.entity.*;
import com.dtech.kitecon.screener.elliott.repository.*;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRunResultRepository;
import com.dtech.kitecon.screener.elliott.service.*;
import com.dtech.kitecon.service.copilot.*;
import com.dtech.kitecon.service.copilot.dto.*;
import com.dtech.ta.elliott.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.*;
import com.dtech.kitecon.screener.elliott.dto.SymbolStatusDto;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ElliottScreenerService {

    private final ElliottScreenerRepository screenerRepository;
    private final ElliottTradeSuggestionRepository suggestionRepository;
    private final ElliottScreenerRunRepository runRepository;
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
    private final ElliottSymbolScanService symbolScanService;
    private final ElliottScreenerRunResultRepository runResultRepository;

    public ElliottScreenerResponse createScreener(Long userId, ElliottScreenerRequest request) {
        Instant nextRunAt = computeNextRunAt(request.getScheduleCron());
        ElliottScreener screener = ElliottScreener.builder()
                .userId(userId)
                .name(request.getName())
                .symbols(request.getSymbols())
                .timeframes(request.getTimeframes())
                .primaryTimeframe(request.getPrimaryTimeframe())
                .scheduleCron(request.getScheduleCron())
                .enabled(true)
                .nextRunAt(nextRunAt)
                .build();
        return ElliottScreenerResponse.from(screenerRepository.save(screener));
    }

    public ElliottScreenerResponse updateScreener(Long userId, Long screenerId, ElliottScreenerRequest request) {
        ElliottScreener screener = screenerRepository.findByIdAndUserId(screenerId, userId)
                .orElseThrow(() -> new IllegalStateException("Screener not found or access denied"));
        screener.setName(request.getName());
        screener.setSymbols(request.getSymbols());
        screener.setTimeframes(request.getTimeframes());
        screener.setPrimaryTimeframe(request.getPrimaryTimeframe());
        screener.setScheduleCron(request.getScheduleCron());
        screener.setNextRunAt(computeNextRunAt(request.getScheduleCron()));
        return ElliottScreenerResponse.from(screenerRepository.save(screener));
    }

    public void deleteScreener(Long userId, Long screenerId) {
        ElliottScreener screener = screenerRepository.findByIdAndUserId(screenerId, userId)
                .orElseThrow(() -> new IllegalStateException("Screener not found or access denied"));
        screener.setEnabled(false);
        screenerRepository.save(screener);
    }

    public ElliottScreenerResponse getScreener(Long userId, Long screenerId) {
        ElliottScreener screener = screenerRepository.findByIdAndUserId(screenerId, userId)
                .orElseThrow(() -> new IllegalStateException("Screener not found or access denied"));
        return ElliottScreenerResponse.from(screener);
    }

    public List<ElliottScreenerResponse> listScreeners(Long userId) {
        return screenerRepository.findByUserId(userId).stream()
                .map(ElliottScreenerResponse::from)
                .toList();
    }

    public ElliottScreenerRunResponse triggerNow(Long userId, Long screenerId) {
        ElliottScreener screener = screenerRepository.findByIdAndUserId(screenerId, userId)
                .orElseThrow(() -> new IllegalStateException("Screener not found or access denied"));
        return runScreener(screener, userId);
    }

    public ElliottScreenerRunResponse runScreener(ElliottScreener screener, Long userId) {
        ElliottScreenerRun run = ElliottScreenerRun.builder()
                .screenerId(screener.getId())
                .status("RUNNING")
                .startedAt(Instant.now())
                .build();
        run = runRepository.save(run);

        List<String> symbolList = Arrays.stream(screener.getSymbols().split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        run.setTotalSymbols(symbolList.size());
        int processed = 0, created = 0, skipped = 0;
        StringBuilder errors = new StringBuilder();

        for (String symbol : symbolList) {
            String status = symbolScanService.scanSymbol(screener, run.getId(), userId, symbol);
            switch (status) {
                case "PASSED" -> created++;
                case "SKIPPED" -> skipped++;
                case "ERROR" -> errors.append(symbol).append(": error\n");
                // FAILED = no setup found, counted in processed only
            }
            processed++;
            run.setProcessedSymbols(processed);
        }

        run.setStatus("COMPLETED");
        run.setCompletedAt(Instant.now());
        run.setSuggestionsCreated(created);
        run.setDuplicatesSkipped(skipped);
        run.setErrorSummary(errors.isEmpty() ? null : errors.toString());
        run = runRepository.save(run);

        screener.setLastRunAt(Instant.now());
        screener.setNextRunAt(computeNextRunAt(screener.getScheduleCron()));
        screenerRepository.save(screener);

        return ElliottScreenerRunResponse.from(run);
    }

    boolean runForSymbol(ElliottScreener screener, Long runId, Long userId, String symbol) throws Exception {
        var instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) throw new IllegalArgumentException("Instrument not found: " + symbol);

        List<String> tfs = Arrays.stream(screener.getTimeframes().split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
        Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
        Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();
        List<String> orderedTfs = new ArrayList<>();
        StringBuilder msdSummary = new StringBuilder();

        for (String tf : tfs) {
            Interval interval = mapTimeframeToInterval(tf);
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
                ? primarySeries.getLastBar().getClosePrice().doubleValue()
                : 0.0;

        ProcessAnalysisResponse processed = analysisProcessService.process(
                advResult.getWaveAnalysis(), symbol, currentPrice, primaryTf);

        String data = "=== SYMBOL: " + symbol + " ===\n"
                + processed.getHumanReadable()
                + "\n" + msdSummary;

        String instructions = orchestratorService.buildScenarioEvaluatorInstructions();
        String rawResponse = aiService.call(userId, instructions, data);

        AIResponse aiResponse = responseParser.parse(rawResponse);
        if (!(aiResponse instanceof FindingResponse finding)) return false;
        if (!"ENTRY_READY".equals(finding.getCurrentStage())) return false;
        if (finding.getAnticipatoryEntry() == null) return false;

        String direction = deriveDirection(finding);
        if (direction == null) return false;

        boolean duplicate = suggestionRepository.existsByScreenerIdAndSymbolAndDirectionAndStateIn(
                screener.getId(), symbol, direction,
                List.of(SuggestionState.PROPOSED, SuggestionState.ANTICIPATORY, SuggestionState.ACTIVE));
        if (duplicate) return false;

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

        // Generate and save chart layouts
        try {
            Map<String, ElliottWaveAnalysis> analysisByTf = new LinkedHashMap<>();
            for (String tf : orderedTfs) {
                analysisByTf.put(tf, advResult.getWaveAnalysis());
            }
            layoutService.generateAndSaveLayouts(suggestion, analysisByTf, seriesByTf);
        } catch (Exception e) {
            log.warn("Failed to generate chart layouts for suggestion {}: {}", suggestion.getId(), e.getMessage(), e);
        }

        return true;
    }

    public void regenerateLayouts(ElliottTradeSuggestion suggestion) throws Exception {
        String symbol = suggestion.getSymbol();
        var instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) throw new IllegalArgumentException("Instrument not found: " + symbol);

        List<String> tfs = Arrays.stream(suggestion.getAllTimeframes().split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();

        Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
        Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
        Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();
        List<String> orderedTfs = new ArrayList<>();

        for (String tf : tfs) {
            Interval interval = mapTimeframeToInterval(tf);
            if (interval == null) { log.warn("[regenerateLayouts] Unknown tf {}", tf); continue; }
            try {
                List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(symbol, instrument, interval);
                BarSeries series = zigzagService.getBarSeries(symbol, instrument, interval);
                List<ZigZagPoint> pivotsWithLtp = zigzagService.withLtpPivot(pivots, series);
                MarketStructureData msd = marketStructureService.analyse(pivotsWithLtp, tf);
                pivotsByTf.put(tf, pivotsWithLtp);
                structureByTf.put(tf, msd);
                seriesByTf.put(tf, series);
                orderedTfs.add(tf);
            } catch (Exception e) {
                log.warn("[regenerateLayouts] TF {} failed for {}: {}", tf, symbol, e.getMessage());
            }
        }

        if (orderedTfs.isEmpty()) throw new IllegalStateException("No valid timeframes for " + symbol);

        String primaryTf = suggestion.getPrimaryTimeframe() != null
                ? suggestion.getPrimaryTimeframe()
                : orderedTfs.get(orderedTfs.size() - 1);

        AdvancedElliottAnalysisResult advResult = advancedElliottService.analyze(
                seriesByTf, pivotsByTf, structureByTf, orderedTfs, symbol, primaryTf);

        Map<String, ElliottWaveAnalysis> analysisByTf = new LinkedHashMap<>();
        for (String tf : orderedTfs) {
            analysisByTf.put(tf, advResult.getWaveAnalysis());
        }

        layoutService.generateAndSaveLayouts(suggestion, analysisByTf, seriesByTf);
    }

    public List<SymbolStatusDto> getSymbolStatus(Long screenerId) {
        ElliottScreener screener = screenerRepository.findById(screenerId)
                .orElseThrow(() -> new IllegalStateException("Screener not found: " + screenerId));

        List<String> symbolList = Arrays.stream(screener.getSymbols().split(","))
                .map(String::trim).filter(s -> !s.isBlank()).toList();

        List<SuggestionState> activeStates = List.of(
                SuggestionState.PROPOSED, SuggestionState.ANTICIPATORY, SuggestionState.ACTIVE);

        return symbolList.stream().map(symbol -> {
            var latestResult = runResultRepository.findTopByScreenerIdAndSymbolOrderByScannedAtDesc(screenerId, symbol);
            var activeSuggestions = suggestionRepository.findByScreenerIdAndSymbolAndStateIn(screenerId, symbol, activeStates);
            var activeSuggestion = activeSuggestions.stream().findFirst().orElse(null);

            // Prefer the suggestion from the latest result if present
            Long suggestionId = null;
            String suggestionState = null;
            String suggestionDirection = null;
            if (latestResult.isPresent() && latestResult.get().getSuggestionId() != null) {
                suggestionId = latestResult.get().getSuggestionId();
                suggestionRepository.findById(suggestionId).ifPresent(s -> {});
            }
            if (activeSuggestion != null) {
                suggestionId = activeSuggestion.getId();
                suggestionState = activeSuggestion.getState().name();
                suggestionDirection = activeSuggestion.getDirection();
            }

            return SymbolStatusDto.builder()
                    .symbol(symbol)
                    .lastStatus(latestResult.map(r -> r.getStatus()).orElse(null))
                    .lastScannedAt(latestResult.map(r -> r.getScannedAt()).orElse(null))
                    .processingMs(latestResult.map(r -> r.getProcessingMs()).orElse(null))
                    .suggestionId(suggestionId)
                    .suggestionState(suggestionState)
                    .suggestionDirection(suggestionDirection)
                    .runId(latestResult.map(r -> r.getRunId()).orElse(null))
                    .build();
        }).toList();
    }

    private String deriveDirection(FindingResponse finding) {
        String text = "";
        if (finding.getAnticipatoryEntry() != null && finding.getAnticipatoryEntry().getTriggerDescription() != null) {
            text += " " + finding.getAnticipatoryEntry().getTriggerDescription();
        }
        if (finding.getWaveContext() != null) text += " " + finding.getWaveContext();
        if (finding.getHypothesisLabel() != null) text += " " + finding.getHypothesisLabel();

        String lower = text.toLowerCase();
        if (lower.contains("long") || lower.contains("buy") || lower.contains("bullish") || lower.contains("bull")) {
            return "LONG";
        }
        if (lower.contains("short") || lower.contains("sell") || lower.contains("bearish") || lower.contains("bear")) {
            return "SHORT";
        }
        return null;
    }

    public static Interval mapTimeframeToInterval(String tf) {
        if (tf == null) return null;
        return switch (tf.toLowerCase().trim()) {
            case "weekly", "1w", "week" -> Interval.Week;
            case "daily", "1d", "day" -> Interval.Day;
            case "4h", "4hour", "240" -> Interval.FourHours;
            case "1h", "60min", "60minute" -> Interval.OneHour;
            case "30min", "30m", "30minute" -> Interval.ThirtyMinute;
            case "15min", "15m", "15minute" -> Interval.FifteenMinute;
            case "5min", "5m", "5minute" -> Interval.FiveMinute;
            case "3min", "3m", "3minute" -> Interval.ThreeMinute;
            default -> null;
        };
    }

    private Instant computeNextRunAt(String cron) {
        try {
            return CronExpression.parse(cron).next(ZonedDateTime.now()).toInstant();
        } catch (Exception e) {
            log.warn("[ElliottScreener] Could not parse cron '{}': {}", cron, e.getMessage());
            return null;
        }
    }
}
