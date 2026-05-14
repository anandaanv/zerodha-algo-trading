package com.dtech.aitrader.web;

import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.repository.WatchTradeRepository;
import com.dtech.aitrader.service.AiAnalyseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/ai-analyse")
@RequiredArgsConstructor
@Slf4j
public class AiAnalyseController {

    private final AiAnalyseService aiAnalyseService;
    private final WatchTradeRepository watchTradeRepository;

    /**
     * POST /api/ai-analyse/run
     * Generates watch trades via AI analysis on a symbol.
     */
    @PostMapping("/run")
    public AiAnalyseResponse run(
            @RequestBody AiAnalyseRequest request,
            Authentication auth) {

        log.info("POST /api/ai-analyse/run - symbol={}, tabId={}, layoutId={}, timeframe={}, user={}",
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
            log.error("Error in /api/ai-analyse/run", e);
            throw new RuntimeException("AI Analyse run failed: " + e.getMessage(), e);
        }
    }

    private Long extractUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("User not authenticated");
        }
        return 1L; // Hardcoded for now
    }

    // Request/Response DTOs
    public static class AiAnalyseRequest {
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

    public static class AiAnalyseResponse {
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
}
