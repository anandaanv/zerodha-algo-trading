package com.dtech.aitrader.service;

import com.dtech.aitrader.data.PaperTrade;
import com.dtech.aitrader.model.ExitDecision;
import com.dtech.aitrader.repository.AiTraderConfigRepository;
import com.dtech.aitrader.repository.PaperTradeRepository;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * TrailStopManager is a scheduled orchestrator that periodically checks open paper trades,
 * fetches current prices, and calls ExitAgent to decide on stop/target adjustments.
 *
 * Scheduled to run every 5 minutes during market hours (9 AM - 3 PM IST, Monday-Friday).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TrailStopManager {
    private final PaperTradeRepository paperTradeRepository;
    private final ExitAgentService exitAgentService;
    private final ChartDataService chartDataService;
    private final AiTraderConfigRepository configRepository;

    /**
     * Scheduled tick: every 5 minutes during market hours (Asia/Kolkata timezone).
     * Cron: minute=0,5,10,15,20,25,30,35,40,45,50,55 hour=9-15 day=MON-FRI
     */
    @Scheduled(cron = "0 */5 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void tick() {
        // Check if trail-stop is enabled
        boolean enabled = isConfigEnabled("trail_stop.enabled");
        if (!enabled) {
            log.debug("trail_stop.enabled=false, skipping tick");
            return;
        }

        log.info("TrailStopManager tick: checking open trades");

        List<PaperTrade> openTrades = paperTradeRepository.findByStatus("OPEN");
        if (openTrades.isEmpty()) {
            log.debug("No open trades to check");
            return;
        }

        for (PaperTrade trade : openTrades) {
            try {
                processTrade(trade);
            } catch (Exception e) {
                log.error("Error processing trade {}: {}", trade.getId(), e.getMessage(), e);
            }
        }
    }

    private void processTrade(PaperTrade trade) {
        // Get current price from latest hourly candle
        Double currentPrice = getCurrentPrice(trade.getSymbol());
        if (currentPrice == null) {
            log.warn("Could not get current price for {}", trade.getSymbol());
            return;
        }

        log.debug("Processing trade {}: symbol={}, direction={}, entry={}, sl={}, target={}, current={}",
            trade.getId(), trade.getSymbol(), trade.getDirection(),
            trade.getEntryPrice(), trade.getSl(), trade.getTarget(), currentPrice);

        // Hard SL/TP hit check (rules-only, no AI for clear hits)
        if (trade.getDirection().equals("LONG")) {
            if (currentPrice <= trade.getSl().doubleValue()) {
                closeTrade(trade, currentPrice, "STOP");
                return;
            }
            if (currentPrice >= trade.getTarget().doubleValue()) {
                // Price hit target -- AI decides: actually close or extend?
                ExitDecision decision = exitAgentService.decide(trade, 1L); // TODO: resolve actual userId
                applyDecision(trade, decision, currentPrice);
                return;
            }
        } else if (trade.getDirection().equals("SHORT")) {
            if (currentPrice >= trade.getSl().doubleValue()) {
                closeTrade(trade, currentPrice, "STOP");
                return;
            }
            if (currentPrice <= trade.getTarget().doubleValue()) {
                // Price hit target -- AI decides: actually close or extend?
                ExitDecision decision = exitAgentService.decide(trade, 1L); // TODO: resolve actual userId
                applyDecision(trade, decision, currentPrice);
                return;
            }
        }

        // Otherwise periodic AI check based on interval
        int checkIntervalHours = getConfigInt("trail_stop.check_interval_hours", 4);
        if (shouldCheckTrade(trade, checkIntervalHours)) {
            log.debug("Trade {} needs periodic check (last checked {} hours ago)",
                trade.getId(), hoursAgoSinceCheck(trade));
            ExitDecision decision = exitAgentService.decide(trade, 1L); // TODO: resolve actual userId
            applyDecision(trade, decision, currentPrice);
        }
    }

    private Double getCurrentPrice(String symbol) {
        try {
            List<OhlcBarDTO> bars = chartDataService.getBars(symbol, "OneHour", null, null, true);
            if (bars != null && !bars.isEmpty()) {
                return bars.get(bars.size() - 1).getClose();
            }
        } catch (Exception e) {
            log.warn("Failed to get current price for {}: {}", symbol, e.getMessage());
        }
        return null;
    }

    private boolean shouldCheckTrade(PaperTrade trade, int checkIntervalHours) {
        if (trade.getLastCheckedAt() == null) {
            return true; // Never checked before
        }
        long hoursSinceCheck = ChronoUnit.HOURS.between(trade.getLastCheckedAt(), LocalDateTime.now());
        return hoursSinceCheck >= checkIntervalHours;
    }

    private long hoursAgoSinceCheck(PaperTrade trade) {
        if (trade.getLastCheckedAt() == null) {
            return Long.MAX_VALUE;
        }
        return ChronoUnit.HOURS.between(trade.getLastCheckedAt(), LocalDateTime.now());
    }

    private void closeTrade(PaperTrade trade, Double exitPrice, String reason) {
        trade.setClosedAt(LocalDateTime.now());
        trade.setExitPrice(new BigDecimal(exitPrice));
        trade.setExitReason(reason);
        trade.setStatus("CLOSED");

        // Calculate P&L
        double pnl;
        if (trade.getDirection().equals("LONG")) {
            pnl = (exitPrice - trade.getEntryPrice().doubleValue()) / trade.getEntryPrice().doubleValue() * 100;
        } else {
            pnl = (trade.getEntryPrice().doubleValue() - exitPrice) / trade.getEntryPrice().doubleValue() * 100;
        }
        trade.setPnlPct(pnl);

        paperTradeRepository.save(trade);
        log.info("Closed trade {}: reason={}, exitPrice={}, pnlPct={}%",
            trade.getId(), reason, exitPrice, String.format("%.2f", pnl));
    }

    private void applyDecision(PaperTrade trade, ExitDecision decision, Double currentPrice) {
        switch (decision.action()) {
            case "CLOSE_NOW":
                closeTrade(trade, currentPrice, "AI_CLOSE");
                break;

            case "MOVE_TO_BREAKEVEN":
                trade.setSl(trade.getEntryPrice());
                trade.setLastCheckedAt(LocalDateTime.now());
                paperTradeRepository.save(trade);
                log.info("Trade {}: moved SL to breakeven (entry={})", trade.getId(), trade.getEntryPrice());
                break;

            case "TRAIL_TO_X":
                if (decision.newStop() != null) {
                    trade.setSl(new BigDecimal(decision.newStop()));
                    trade.setLastCheckedAt(LocalDateTime.now());
                    paperTradeRepository.save(trade);
                    log.info("Trade {}: trailed SL to {}", trade.getId(), decision.newStop());
                }
                break;

            case "EXTEND_TARGET_TO_Y":
                if (decision.newTarget() != null) {
                    trade.setTarget(new BigDecimal(decision.newTarget()));
                    trade.setLastCheckedAt(LocalDateTime.now());
                    paperTradeRepository.save(trade);
                    log.info("Trade {}: extended target to {}", trade.getId(), decision.newTarget());
                }
                break;

            case "HOLD":
            default:
                trade.setLastCheckedAt(LocalDateTime.now());
                paperTradeRepository.save(trade);
                log.debug("Trade {}: holding", trade.getId());
                break;
        }
    }

    private boolean isConfigEnabled(String key) {
        Optional<com.dtech.aitrader.data.AiTraderConfig> config = configRepository.findByConfigKey(key);
        return config.isPresent() && "true".equalsIgnoreCase(config.get().getConfigValue());
    }

    private int getConfigInt(String key, int defaultValue) {
        Optional<com.dtech.aitrader.data.AiTraderConfig> config = configRepository.findByConfigKey(key);
        if (config.isPresent()) {
            try {
                return Integer.parseInt(config.get().getConfigValue());
            } catch (NumberFormatException e) {
                log.warn("Invalid integer config {}: {}", key, config.get().getConfigValue());
            }
        }
        return defaultValue;
    }
}
