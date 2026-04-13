package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.patternscanner.PatternDto;
import com.dtech.kitecon.patternscanner.PatternScanService;
import com.dtech.kitecon.patternscanner.TradeFilterClient;
import com.dtech.kitecon.trade.entity.TradeExecution;
import com.dtech.kitecon.trade.entity.TradeMonitorLog;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.MonitorAction;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeExecutionRepository;
import com.dtech.kitecon.trade.repository.TradeMonitorLogRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Handles trade signals in WATCHING_ENTRY and ENTRY_PENDING states.
 *
 * WATCHING_ENTRY:
 *   - Checks if the entry window has expired → EXPIRED
 *   - Fetches LTP and checks if price has crossed the entry level
 *   - In dry run: simulates a fill immediately at LTP → ACTIVE
 *   - In live:   places a market order → ENTRY_PENDING
 *
 * ENTRY_PENDING:
 *   - In dry run: no-op (skipped — dry run goes straight to ACTIVE)
 *   - In live:   checks broker order fill status → ACTIVE when filled
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeEntryHandler {

    @Value("${trade.filter.threshold:0.82}")
    private double mlFilterThreshold;

    @Value("${trade.monitor.notional:1000000}")
    private long notionalPerLot;

    @Value("${trade.monitor.margin:300000}")
    private long marginPerLot;

    private final BrokerOrderService brokerOrderService;
    private final TradeSignalRepository signalRepository;
    private final TradeExecutionRepository executionRepository;
    private final TradeMonitorLogRepository logRepository;
    private final TradeOrchestrationService tradeOrchestrationService;
    private final PatternScanService patternScanService;
    private final TradeFilterClient tradeFilterClient;

    @Transactional
    public void handle(TradeSignal signal, boolean dryRun) {
        // Check entry window expiry first
        if (signal.getEntryValidUntil() != null && Instant.now().isAfter(signal.getEntryValidUntil())) {
            signal.setStatus(TradeStatus.EXPIRED);
            signalRepository.save(signal);
            writeLog(signal, null, null, MonitorAction.SIGNAL_EXPIRED,
                    "Entry window expired at " + signal.getEntryValidUntil(), dryRun);
            log.info("[EntryHandler] Signal {} EXPIRED — entry window closed", signal.getId());
            return;
        }

        BigDecimal ltp = brokerOrderService.fetchLtp(signal.getSymbol(), signal.getInstrumentToken());
        if (ltp == null) {
            writeLog(signal, null, null, MonitorAction.NONE, "LTP unavailable — skipping tick", dryRun);
            return;
        }

        boolean entryTriggered = isEntryTriggered(signal, ltp);

        if (entryTriggered) {
            double score = scoreMlAtEntry(signal);
            signal.setMlScore(BigDecimal.valueOf(score).setScale(4, java.math.RoundingMode.HALF_UP));
            if (score < mlFilterThreshold) {
                signal.setStatus(TradeStatus.EXPIRED);
                signalRepository.save(signal);
                writeLog(signal, ltp, null, MonitorAction.SIGNAL_EXPIRED,
                        "ML filter rejected at entry: score=" + score + " threshold=" + mlFilterThreshold, dryRun);
                log.info("[EntryHandler] Signal {} {} ML-filtered at entry — score={} threshold={}",
                        signal.getId(), signal.getSymbol(), score, mlFilterThreshold);
                return;
            }
        }

        if (!entryTriggered) {
            String msg = String.format("Watching entry: LTP=%.4f entry=%.4f SL=%.4f T=%.4f",
                    ltp.doubleValue(),
                    signal.getEntryPrice().doubleValue(),
                    signal.getStopLoss().doubleValue(),
                    signal.getTarget().doubleValue());
            writeLog(signal, ltp, null, MonitorAction.NONE, msg, dryRun);
            return;
        }

        log.info("[EntryHandler] Entry triggered for signal {} {} {} at LTP={}",
                signal.getId(), signal.getSymbol(), signal.getDirection(), ltp);

        if (dryRun) {
            // Simulate fill at current LTP
            TradeExecution execution = createExecution(signal, ltp, "DRY-RUN-ENTRY-" + signal.getId());
            executionRepository.save(execution);
            signal.setStatus(TradeStatus.ACTIVE);
            signalRepository.save(signal);
            tradeOrchestrationService.onEntryTriggered(signal);
            writeLog(signal, ltp, null, MonitorAction.ENTRY_ORDER_PLACED,
                    "[DRY RUN] Entry simulated at " + ltp, dryRun);
        } else {
            String orderId = brokerOrderService.placeMarketOrder(signal);
            signal.setStatus(TradeStatus.ENTRY_PENDING);
            signal.setNotes(coalesce(signal.getNotes(), "") + " entryOrder=" + orderId);
            signalRepository.save(signal);
            writeLog(signal, ltp, null, MonitorAction.ENTRY_ORDER_PLACED,
                    "Entry order placed: " + orderId, dryRun);
        }
    }

    @Transactional
    public void checkFill(TradeSignal signal, boolean dryRun) {
        if (dryRun) {
            // In dry run, ENTRY_PENDING should not occur — handle gracefully
            log.warn("[EntryHandler] Signal {} in ENTRY_PENDING during dry run — resetting to ACTIVE", signal.getId());
            signal.setStatus(TradeStatus.ACTIVE);
            signalRepository.save(signal);
            return;
        }
        // TODO: check broker order fill status
        // String orderId = extractOrderId(signal.getNotes());
        // if (brokerOrderService.isOrderFilled(orderId)) {
        //     BigDecimal fillPrice = brokerOrderService.getFillPrice(orderId);
        //     TradeExecution execution = createExecution(signal, fillPrice, orderId);
        //     executionRepository.save(execution);
        //     signal.setStatus(TradeStatus.ACTIVE);
        //     signalRepository.save(signal);
        //     writeLog(signal, fillPrice, null, MonitorAction.ENTRY_ORDER_PLACED, "Entry filled at " + fillPrice, dryRun);
        // }
        log.info("[EntryHandler] checkFill for signal {} — live order status check not yet implemented", signal.getId());
    }

    private double scoreMlAtEntry(TradeSignal signal) {
        try {
            PatternDto indicators = patternScanService.computeCurrentIndicators(signal);
            if (indicators == null) {
                log.warn("[EntryHandler] Could not compute indicators for {} — failing open", signal.getSymbol());
                return 1.0;
            }
            double score = tradeFilterClient.score(
                    indicators, signal.getPatternType(),
                    indicators.getDailyRsi(), indicators.getDailyAdx(), indicators.getDailyAdxEma(),
                    indicators.getAdxWatching(), indicators.getAdxWatchingEma(),
                    indicators.getAdxConfirm(), indicators.getAdxConfirmEma(),
                    indicators.getMacdWatching(), indicators.getMacdSignalWatching(),
                    indicators.getBbWidthWatching(), indicators.getBbPctBWatching(),
                    indicators.getMacdDaily(), indicators.getMacdSignalDaily(),
                    indicators.getBbWidthDaily(), indicators.getBbPctBDaily(),
                    indicators.getBbExpanding(), indicators.getBbAligned(),
                    indicators.getRsiSlope(), indicators.getMacdHistSlope(), indicators.getAdxSlope()
            );
            log.info("[EntryHandler] ML score for signal {} {}: {}", signal.getId(), signal.getSymbol(), score);
            return score;
        } catch (Exception e) {
            log.warn("[EntryHandler] ML scoring failed for signal {} — failing open: {}", signal.getId(), e.getMessage());
            return 1.0;
        }
    }

    private boolean isEntryTriggered(TradeSignal signal, BigDecimal ltp) {
        if (signal.getDirection() == TradeDirection.LONG) {
            return ltp.compareTo(signal.getEntryPrice()) >= 0;
        } else {
            return ltp.compareTo(signal.getEntryPrice()) <= 0;
        }
    }

    private TradeExecution createExecution(TradeSignal signal, BigDecimal entryPrice, String orderId) {
        return TradeExecution.builder()
                .signal(signal)
                .entryOrderId(orderId)
                .entryPriceActual(entryPrice)
                .entryTime(Instant.now())
                .quantity(signal.getLotSize() != null ? signal.getLotSize() : 1)
                .notionalValue(BigDecimal.valueOf(notionalPerLot))
                .marginDeployed(BigDecimal.valueOf(marginPerLot))
                .build();
    }

    private void writeLog(TradeSignal signal, BigDecimal price, BigDecimal unrealisedPnl,
                          MonitorAction action, String detail, boolean dryRun) {
        logRepository.save(TradeMonitorLog.builder()
                .signal(signal)
                .checkedAt(Instant.now())
                .currentPrice(price)
                .unrealisedPnlInr(unrealisedPnl)
                .actionTaken(action)
                .actionDetail(detail)
                .dryRun(dryRun)
                .workerVersion("1.0.0")
                .build());
    }

    private String coalesce(String a, String b) {
        return a != null ? a : b;
    }
}
