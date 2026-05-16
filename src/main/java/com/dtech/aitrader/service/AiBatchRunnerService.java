package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevels;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class AiBatchRunnerService {
    private final LevelsAgentService levelsAgentService;
    private final AiDrawingsMerger aiDrawingsMerger;
    private final SimpMessagingTemplate simpMessagingTemplate;
    private final ObjectMapper objectMapper;

    @Async("aiBatchExecutor")
    public CompletableFuture<Void> processSymbol(
            String symbol,
            String tabId,
            Long layoutId,
            String timeframe,
            Long userId,
            String batchId) {

        long startTime = System.currentTimeMillis();
        try {
            log.info("Starting batch task for symbol={}, batchId={}", symbol, batchId);

            // Run levels agent
            AiLevels aiLevels = levelsAgentService.runForSymbol(symbol, userId);

            // Parse levels JSON
            JsonNode levelsJson = objectMapper.readTree(aiLevels.getLevelsJson());

            // Merge into chart state
            aiDrawingsMerger.mergeIntoChartState(
                    symbol,
                    tabId,
                    layoutId,
                    timeframe,
                    levelsJson
            );

            long durationMs = System.currentTimeMillis() - startTime;

            // Publish success message
            Map<String, Object> progressMsg = new HashMap<>();
            progressMsg.put("symbol", symbol);
            progressMsg.put("status", "success");
            progressMsg.put("costUsd", aiLevels.getCostUsd());
            progressMsg.put("durationMs", durationMs);

            simpMessagingTemplate.convertAndSend(
                    "/topic/ai-levels/progress/" + batchId,
                    progressMsg
            );

            log.info("Completed batch task for symbol={}, batchId={}, duration={}ms", symbol, batchId, durationMs);

            return CompletableFuture.completedFuture(null);

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log.error("Error processing symbol={} in batch={}", symbol, batchId, e);

            // Publish error message
            Map<String, Object> errorMsg = new HashMap<>();
            errorMsg.put("symbol", symbol);
            errorMsg.put("status", "error");
            errorMsg.put("error", e.getMessage());
            errorMsg.put("durationMs", durationMs);

            simpMessagingTemplate.convertAndSend(
                    "/topic/ai-levels/progress/" + batchId,
                    errorMsg
            );

            return CompletableFuture.failedFuture(e);
        }
    }
}
