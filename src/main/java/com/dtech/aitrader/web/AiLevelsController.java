package com.dtech.aitrader.web;

import com.dtech.aitrader.data.AiLevels;
import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.repository.AiLevelsRepository;
import com.dtech.aitrader.repository.WatchTradeRepository;
import com.dtech.aitrader.service.AiDrawingsMerger;
import com.dtech.aitrader.service.AiBatchRunnerService;
import com.dtech.aitrader.service.AiAnalyseService;
import com.dtech.aitrader.service.LevelsAgentService;
import com.dtech.aitrader.service.MergeResult;
import com.dtech.kitecon.repository.IndexSymbolRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import lombok.Builder;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/ai-levels")
@RequiredArgsConstructor
@Slf4j
public class AiLevelsController {
    private final LevelsAgentService levelsAgentService;
    private final AiAnalyseService aiAnalyseService;
    private final AiDrawingsMerger aiDrawingsMerger;
    private final AiBatchRunnerService aiBatchRunnerService;
    private final AiLevelsRepository aiLevelsRepository;
    private final WatchTradeRepository watchTradeRepository;
    private final IndexSymbolRepository indexSymbolRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/run")
    public AiLevelsResponse runLevels(
            @RequestBody AiLevelsRequest request,
            Authentication auth) {

        log.info("POST /api/ai-levels/run - symbol={}, tabId={}, layoutId={}, timeframe={}, user={}",
                request.getSymbol(), request.getTabId(), request.getLayoutId(), request.getTimeframe(), auth.getName());

        try {
            // Extract user ID from authentication
            Long userId = extractUserId(auth);

            // Run levels agent
            AiLevels aiLevels = levelsAgentService.runForSymbol(request.getSymbol(), userId);

            // Parse levels JSON
            JsonNode levelsJson = objectMapper.readTree(aiLevels.getLevelsJson());

            // Merge into chart state
            MergeResult mergeResult = aiDrawingsMerger.mergeIntoChartState(
                    request.getSymbol(),
                    request.getTabId(),
                    request.getLayoutId(),
                    request.getTimeframe(),
                    levelsJson
            );

            // Build response
            AiLevelsResponse response = AiLevelsResponse.builder()
                    .symbol(request.getSymbol())
                    .generatedAt(aiLevels.getGeneratedAt().toString())
                    .levelsJson(levelsJson)
                    .costUsd(aiLevels.getCostUsd())
                    .modelUsed(aiLevels.getModelUsed())
                    .inputTokens(aiLevels.getInputTokens())
                    .outputTokens(aiLevels.getOutputTokens())
                    .linesMergedCount(mergeResult.getMergedCount())
                    .linesSuppressedCount(mergeResult.getSuppressedCount())
                    .build();

            return response;

        } catch (Exception e) {
            log.error("Error in /api/ai-levels/run", e);
            throw new RuntimeException("AI levels run failed: " + e.getMessage(), e);
        }
    }

    @GetMapping("/symbols/fno")
    public List<SymbolStatus> listFnoWithStatus() {
        log.info("GET /api/ai-levels/symbols/fno");
        try {
            List<String> symbols = indexSymbolRepository.findAllSymbolsByIndexName("FNO");
            log.info("Found {} FNO symbols", symbols.size());

            return symbols.stream()
                    .map(symbol -> {
                        Optional<AiLevels> latest = aiLevelsRepository.findFirstBySymbolOrderByGeneratedAtDesc(symbol);
                        return new SymbolStatus(symbol, latest.map(AiLevels::getGeneratedAt).orElse(null));
                    })
                    .sorted(Comparator.comparing(SymbolStatus::symbol))
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Error listing FNO symbols", e);
            throw new RuntimeException("Failed to list FNO symbols: " + e.getMessage(), e);
        }
    }

    @PostMapping("/run-batch")
    public RunBatchResponse runBatch(
            @RequestBody RunBatchRequest request,
            Authentication auth) {

        log.info("POST /api/ai-levels/run-batch - symbols.size={}, batchId will be generated, tabId={}, layoutId={}, user={}",
                request.getSymbols().size(), request.getTabId(), request.getLayoutId(), auth.getName());

        try {
            Long userId = extractUserId(auth);
            String batchId = UUID.randomUUID().toString();

            // Schedule async tasks for each symbol (non-blocking)
            for (String symbol : request.getSymbols()) {
                aiBatchRunnerService.processSymbol(
                        symbol,
                        request.getTabId(),
                        request.getLayoutId(),
                        request.getTimeframe(),
                        userId,
                        batchId
                );
            }

            log.info("Queued {} symbols for batch run: batchId={}", request.getSymbols().size(), batchId);

            return new RunBatchResponse(batchId, request.getSymbols().size());

        } catch (Exception e) {
            log.error("Error in /api/ai-levels/run-batch", e);
            throw new RuntimeException("Batch run failed: " + e.getMessage(), e);
        }
    }

    @GetMapping("/watch-trades")
    public List<WatchTradeResponse> getWatchTrades(Authentication auth) {
        log.info("GET /api/ai-levels/watch-trades - user={}", auth.getName());

        try {
            LocalDate today = LocalDate.now();
            List<AiLevels> aiLevelsList = aiLevelsRepository.findAll().stream()
                    .filter(al -> al.getGeneratedForDate().equals(today) || al.getGeneratedForDate().isAfter(today))
                    .collect(Collectors.toList());

            log.info("Found {} AiLevels records for today or after", aiLevelsList.size());

            List<WatchTradeResponse> allWatchTrades = new ArrayList<>();

            for (AiLevels aiLevels : aiLevelsList) {
                try {
                    JsonNode levelsJson = objectMapper.readTree(aiLevels.getLevelsJson());
                    JsonNode watchTradesNode = levelsJson.get("watch_trades");

                    if (watchTradesNode != null && watchTradesNode.isArray()) {
                        for (JsonNode wt : watchTradesNode) {
                            WatchTradeResponse response = new WatchTradeResponse();
                            response.setSymbol(aiLevels.getSymbol());
                            response.setId(wt.get("id").asText());
                            response.setDirection(wt.get("direction").asText());
                            response.setTriggerCondition(wt.get("trigger_condition").asText());
                            response.setEntry(new BigDecimal(wt.get("entry").asText()));
                            response.setStop(new BigDecimal(wt.get("stop").asText()));
                            response.setTarget(new BigDecimal(wt.get("target").asText()));
                            response.setRr(new BigDecimal(wt.get("rr").asText()));
                            response.setConfidence(new BigDecimal(wt.get("confidence").asText()));
                            response.setValidityUntil(wt.get("validity_until").asText());
                            response.setRationale(wt.get("rationale").asText());

                            allWatchTrades.add(response);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Error parsing watch_trades for symbol={}, skipping", aiLevels.getSymbol(), e);
                }
            }

            // Sort by confidence descending
            allWatchTrades.sort((a, b) -> b.getConfidence().compareTo(a.getConfidence()));

            log.info("Returning {} watch_trades sorted by confidence", allWatchTrades.size());

            return allWatchTrades;

        } catch (Exception e) {
            log.error("Error in /api/ai-levels/watch-trades", e);
            throw new RuntimeException("Failed to fetch watch trades: " + e.getMessage(), e);
        }
    }

    @PostMapping("/analyse/run")
    public AiAnalyseResponse analyseRun(
            @RequestBody AiAnalyseRequest request,
            Authentication auth) {

        log.info("POST /api/ai-levels/analyse/run - symbol={}, tabId={}, layoutId={}, timeframe={}, user={}",
                request.getSymbol(), request.getTabId(), request.getLayoutId(), request.getTimeframe(), auth.getName());

        try {
            Long userId = extractUserId(auth);

            // Run AI Analyse service
            List<WatchTrade> watchTrades = aiAnalyseService.runForSymbol(
                    request.getSymbol(),
                    request.getTabId(),
                    request.getLayoutId(),
                    request.getTimeframe(),
                    userId
            );

            // Build response
            AiAnalyseResponse response = AiAnalyseResponse.builder()
                    .symbol(request.getSymbol())
                    .watchTrades(watchTrades)
                    .generatedAt(LocalDate.now().toString())
                    .build();

            return response;

        } catch (Exception e) {
            log.error("Error in /api/ai-levels/analyse/run", e);
            throw new RuntimeException("AI Analyse run failed: " + e.getMessage(), e);
        }
    }

    private Long extractUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("User not authenticated");
        }
        // Return anand's user ID (hardcoded for now; will be fixed with proper UserRepository integration)
        return 1L;
    }
}

// Request/Response DTOs
class AiAnalyseRequest {
    public String symbol;
    public String tabId;
    public Long layoutId;
    public String timeframe;

    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public String getTabId() { return tabId; }
    public void setTabId(String tabId) { this.tabId = tabId; }
    public Long getLayoutId() { return layoutId; }
    public void setLayoutId(Long layoutId) { this.layoutId = layoutId; }
    public String getTimeframe() { return timeframe; }
    public void setTimeframe(String timeframe) { this.timeframe = timeframe; }
}

class AiAnalyseResponse {
    public String symbol;
    public List<WatchTrade> watchTrades;
    public String generatedAt;

    @lombok.Builder
    public AiAnalyseResponse(String symbol, List<WatchTrade> watchTrades, String generatedAt) {
        this.symbol = symbol;
        this.watchTrades = watchTrades;
        this.generatedAt = generatedAt;
    }

    public String getSymbol() { return symbol; }
    public List<WatchTrade> getWatchTrades() { return watchTrades; }
    public String getGeneratedAt() { return generatedAt; }
}
