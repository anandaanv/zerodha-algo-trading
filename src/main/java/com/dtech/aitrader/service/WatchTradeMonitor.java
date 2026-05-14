package com.dtech.aitrader.service;

import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.event.PatternSignalEvent;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.WatchTradeRepository;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Phase 3 WatchTradeMonitor: evaluates watch_trade rows for trigger conditions.
 * Currently supports LEVEL_BREAK and LEVEL_BOUNCE triggers.
 *
 * Future phases will add: TL_BREAK, TL_RETEST, PATTERN_FORM.
 * On trigger, emits PatternSignalEvent for PatternAgent pickup.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WatchTradeMonitor {
    private final WatchTradeRepository watchTradeRepo;
    private final AiTraderConfigService configService;
    private final ChartDataService chartDataService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * Runs every 5 minutes during market hours (9:15 AM - 3:15 PM IST, Mon-Fri).
     * Checks validity and evaluates trigger conditions for watching trades.
     */
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void tick() {
        if (!configService.getBoolean("watch_trade.monitor.enabled", false)) {
            log.debug("WatchTradeMonitor: disabled, skipping tick");
            return;
        }

        List<WatchTrade> watching = watchTradeRepo.findByStatus("WATCHING");
        log.info("WatchTradeMonitor: ticking, {} watching", watching.size());

        LocalDateTime now = LocalDateTime.now();

        for (WatchTrade wt : watching) {
            // Check expiry
            if (wt.getValidityUntil() != null && wt.getValidityUntil().isBefore(now)) {
                wt.setStatus("EXPIRED");
                watchTradeRepo.save(wt);
                log.info("WatchTrade #{} EXPIRED", wt.getId());
                continue;
            }

            // For v1, only evaluate LEVEL_BREAK and LEVEL_BOUNCE
            String trigger = wt.getTriggerType();
            if ("LEVEL_BREAK".equals(trigger) || "LEVEL_BOUNCE".equals(trigger)) {
                try {
                    if (evaluateLevelTrigger(wt)) {
                        wt.setStatus("TRIGGERED");
                        wt.setTriggeredAt(now);
                        wt.setTriggeredPrice(latestPrice(wt.getSymbol()));
                        watchTradeRepo.save(wt);
                        log.info("WatchTrade #{} TRIGGERED: {} {}", wt.getId(), wt.getSymbol(), wt.getDirection());

                        // Phase 3.5: emit PatternSignalEvent so PatternAgent picks up
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
                    }
                } catch (Exception e) {
                    log.warn("Error evaluating WatchTrade #{}: {}", wt.getId(), e.getMessage());
                }
            } else {
                // TL_BREAK, TL_RETEST, PATTERN_FORM — not yet implemented
                log.debug("WatchTrade #{} trigger_type {} not yet evaluable, skipping", wt.getId(), trigger);
            }
        }
    }

    /**
     * Evaluates whether a level trigger has fired.
     * Reads direction and target price from trigger_spec_json.
     *
     * @param wt the watch trade with trigger spec
     * @return true if the trigger condition is met
     */
    private boolean evaluateLevelTrigger(WatchTrade wt) throws Exception {
        JsonNode spec = objectMapper.readTree(
            wt.getTriggerSpecJson() != null ? wt.getTriggerSpecJson() : "{}"
        );

        double targetPrice = spec.path("price").asDouble(
            wt.getEntry() != null ? wt.getEntry().doubleValue() : 0
        );
        String direction = spec.path("direction").asText("above");

        BigDecimal latest = latestPrice(wt.getSymbol());
        if (latest == null) return false;

        boolean fired = "above".equals(direction)
            ? latest.doubleValue() >= targetPrice
            : latest.doubleValue() <= targetPrice;

        return fired;
    }

    /**
     * Fetches the latest closing price for a symbol.
     * Uses ChartDataService to pull 1-hour bars (most recent = latest close).
     *
     * @param symbol the trading symbol (e.g., "RELIANCE", "INFY")
     * @return the latest close price, or null if unavailable
     */
    private BigDecimal latestPrice(String symbol) {
        try {
            List<OhlcBarDTO> bars = chartDataService.getBars(symbol, "OneHour", null, null, true);
            if (bars == null || bars.isEmpty()) return null;

            OhlcBarDTO lastBar = bars.get(bars.size() - 1);
            return BigDecimal.valueOf(lastBar.getClose());
        } catch (Exception e) {
            log.warn("Failed to fetch latest price for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
}
