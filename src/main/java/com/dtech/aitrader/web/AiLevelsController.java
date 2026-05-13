package com.dtech.aitrader.web;

import com.dtech.aitrader.data.AiLevels;
import com.dtech.aitrader.service.AiDrawingsMerger;
import com.dtech.aitrader.service.LevelsAgentService;
import com.dtech.aitrader.service.MergeResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai-levels")
@RequiredArgsConstructor
@Slf4j
public class AiLevelsController {
    private final LevelsAgentService levelsAgentService;
    private final AiDrawingsMerger aiDrawingsMerger;
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

    private Long extractUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("User not authenticated");
        }
        // Return anand's user ID (hardcoded for now; will be fixed with proper UserRepository integration)
        return 1L;
    }
}
