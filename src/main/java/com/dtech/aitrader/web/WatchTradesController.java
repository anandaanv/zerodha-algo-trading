package com.dtech.aitrader.web;

import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.event.PatternSignalEvent;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.WatchTradeRepository;
import com.dtech.aitrader.service.WatchTradeMonitor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/watch-trades")
@RequiredArgsConstructor
@Slf4j
public class WatchTradesController {

    private final WatchTradeRepository watchTradeRepository;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * GET /api/watch-trades?status=WATCHING
     * List watch trades by status.
     */
    @GetMapping
    public List<WatchTrade> getWatchTrades(
            @RequestParam(required = false) String status,
            Authentication auth) {

        log.info("GET /api/watch-trades - status={}, user={}", status, auth.getName());

        try {
            if (status != null && !status.isEmpty()) {
                return watchTradeRepository.findByStatus(status);
            }
            return watchTradeRepository.findAll();

        } catch (Exception e) {
            log.error("Error in GET /api/watch-trades", e);
            throw new RuntimeException("Failed to fetch watch trades: " + e.getMessage(), e);
        }
    }

    /**
     * POST /api/watch-trades/{id}/force-trigger
     * Manually trigger a WATCHING trade and emit PatternSignalEvent.
     * For testing without waiting for actual price trigger.
     */
    @PostMapping("/{id}/force-trigger")
    public Map<String, Object> forceTrigger(
            @PathVariable Long id,
            Authentication auth) {

        log.info("POST /api/watch-trades/{}/force-trigger - user={}", id, auth.getName());

        try {
            WatchTrade wt = watchTradeRepository.findById(id)
                    .orElseThrow(() -> new RuntimeException("WatchTrade #" + id + " not found"));

            if (!"WATCHING".equals(wt.getStatus())) {
                throw new RuntimeException("WatchTrade #" + id + " is not in WATCHING status (current: " + wt.getStatus() + ")");
            }

            LocalDateTime now = LocalDateTime.now();
            wt.setStatus("TRIGGERED");
            wt.setTriggeredAt(now);
            wt.setTriggeredPrice(latestPrice(wt.getSymbol()));
            watchTradeRepository.save(wt);
            log.info("WatchTrade #{} FORCE-TRIGGERED: {} {}", wt.getId(), wt.getSymbol(), wt.getDirection());

            // Emit PatternSignalEvent
            PatternSignal signal = PatternSignal.builder()
                    .symbol(wt.getSymbol())
                    .patternType("WATCH_TRADE_" + wt.getTriggerType())
                    .patternSource("AI_ANALYSE")
                    .signalRef("watch_trade_" + wt.getId())
                    .direction(wt.getDirection())
                    .suggestedEntry(wt.getEntry() != null ? wt.getEntry().doubleValue() : 0)
                    .suggestedSl(wt.getSl() != null ? wt.getSl().doubleValue() : 0)
                    .suggestedTarget(wt.getTarget() != null ? wt.getTarget().doubleValue() : 0)
                    .signalTime(Instant.now())
                    .timeframe("OneHour")
                    .build();
            eventPublisher.publishEvent(new PatternSignalEvent(this, signal));
            log.info("Published PatternSignalEvent for WatchTrade #{}", wt.getId());

            Map<String, Object> response = new HashMap<>();
            response.put("watch_trade_id", wt.getId());
            response.put("status", wt.getStatus());
            response.put("pattern_signal_emitted", true);
            return response;

        } catch (Exception e) {
            log.error("Error force-triggering WatchTrade #{}", id, e);
            throw new RuntimeException("Failed to force-trigger: " + e.getMessage(), e);
        }
    }

    /**
     * Helper: fetch latest closing price for a symbol.
     */
    private BigDecimal latestPrice(String symbol) {
        // For now, return a placeholder. In production, use ChartDataService.
        // This avoids circular dependency for testing purposes.
        return BigDecimal.ZERO;
    }
}
