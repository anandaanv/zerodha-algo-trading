package com.dtech.kitecon.web;

import com.dtech.kitecon.service.StockAnalysisService;
import com.dtech.kitecon.service.TechnicalChatService;
import com.dtech.kitecon.service.model.StockAnalysisResponse;
import com.dtech.kitecon.service.model.TechnicalChatRequest;
import com.dtech.kitecon.service.model.TechnicalChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * StockAnalysisController - Provides stock analysis endpoints
 * Includes fundamentals, news, correlation, and social sentiment
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/analysis")
@CrossOrigin
@Slf4j
public class StockAnalysisController {

    private final StockAnalysisService analysisService;
    private final TechnicalChatService technicalChatService;

    /**
     * Get comprehensive stock analysis
     * POST /api/analysis/analyze
     * Body: { "symbol": "TCS", "timeframe": "1h" }
     */
    @PostMapping("/analyze")
    public ResponseEntity<StockAnalysisResponse> analyzeStock(
        @RequestBody Map<String, String> request,
        Authentication auth
    ) {
        try {
            String symbol = request.get("symbol");
            String timeframe = request.getOrDefault("timeframe", "1d");

            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            log.info("User {} requesting analysis for symbol: {}, timeframe: {}",
                auth.getName(), symbol, timeframe);

            StockAnalysisResponse analysis = analysisService.analyzeStock(symbol, timeframe);

            return ResponseEntity.ok(analysis);
        } catch (Exception e) {
            log.error("Error analyzing stock", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get only fundamentals data
     * GET /api/analysis/fundamentals?symbol=TCS
     */
    @GetMapping("/fundamentals")
    public ResponseEntity<StockAnalysisResponse.FundamentalsData> getFundamentals(
        @RequestParam String symbol,
        Authentication auth
    ) {
        try {
            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            StockAnalysisResponse analysis = analysisService.analyzeStock(symbol, "1d");
            return ResponseEntity.ok(analysis.getFundamentals());
        } catch (Exception e) {
            log.error("Error fetching fundamentals", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get only news data
     * GET /api/analysis/news?symbol=TCS
     */
    @GetMapping("/news")
    public ResponseEntity<?> getNews(
        @RequestParam String symbol,
        Authentication auth
    ) {
        try {
            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            StockAnalysisResponse analysis = analysisService.analyzeStock(symbol, "1d");
            return ResponseEntity.ok(analysis.getNews());
        } catch (Exception e) {
            log.error("Error fetching news", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get only correlation data
     * GET /api/analysis/correlation?symbol=TCS
     */
    @GetMapping("/correlation")
    public ResponseEntity<StockAnalysisResponse.CorrelationData> getCorrelation(
        @RequestParam String symbol,
        Authentication auth
    ) {
        try {
            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            StockAnalysisResponse analysis = analysisService.analyzeStock(symbol, "1d");
            return ResponseEntity.ok(analysis.getCorrelation());
        } catch (Exception e) {
            log.error("Error fetching correlation", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Get only social sentiment data
     * GET /api/analysis/social?symbol=TCS
     */
    @GetMapping("/social")
    public ResponseEntity<StockAnalysisResponse.SocialSentimentData> getSocialSentiment(
        @RequestParam String symbol,
        Authentication auth
    ) {
        try {
            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            StockAnalysisResponse analysis = analysisService.analyzeStock(symbol, "1d");
            return ResponseEntity.ok(analysis.getSocialSentiment());
        } catch (Exception e) {
            log.error("Error fetching social sentiment", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Technical AI chat — REASON or VALIDATE mode
     * POST /api/analysis/technical-chat
     */
    @PostMapping("/technical-chat")
    public ResponseEntity<TechnicalChatResponse> technicalChat(
        @RequestBody TechnicalChatRequest req,
        Authentication auth
    ) {
        if (req.getSymbol() == null || req.getMode() == null) {
            return ResponseEntity.badRequest().build();
        }
        if ("VALIDATE".equals(req.getMode())
            && (req.getUserMessage() == null || req.getUserMessage().isBlank())) {
            return ResponseEntity.badRequest().build();
        }
        try {
            log.info("User {} technical-chat: symbol={}, mode={}", auth.getName(), req.getSymbol(), req.getMode());
            return ResponseEntity.ok(technicalChatService.chat(req));
        } catch (Exception e) {
            log.error("Error in technical-chat", e);
            return ResponseEntity.status(500).build();
        }
    }

    /**
     * Clear cache for a specific symbol (admin/user action)
     * DELETE /api/analysis/cache?symbol=TCS
     */
    @DeleteMapping("/cache")
    public ResponseEntity<?> clearCache(
        @RequestParam String symbol,
        Authentication auth
    ) {
        try {
            if (symbol == null || symbol.trim().isEmpty()) {
                return ResponseEntity.badRequest().build();
            }

            analysisService.invalidateCache(symbol);
            return ResponseEntity.ok(Map.of("status", "cache cleared", "symbol", symbol));
        } catch (Exception e) {
            log.error("Error clearing cache", e);
            return ResponseEntity.status(500).build();
        }
    }
}
