package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.patternscanner.PatternDto;
import com.dtech.kitecon.patternscanner.PatternScanService;
import com.dtech.kitecon.patternscanner.TradeFilterClient;
import com.dtech.kitecon.trade.entity.TradeActionLog;
import com.dtech.kitecon.trade.entity.TradeExecution;
import com.dtech.kitecon.trade.entity.TradeMonitorLog;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.MonitorAction;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeExecutionRepository;
import com.dtech.kitecon.trade.repository.TradeMonitorLogRepository;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.backtest.CandlestickPatternDetector;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.algo.series.Interval;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

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
    private final TradeActionLogger tradeActionLogger;
    private final CandlestickPatternDetector candlestickPatternDetector;
    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;

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

        BarSeries series = getBarSeriesForSignal(signal);
        boolean entryTriggered = isEntryTriggered(signal, ltp, series);

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
        try {
            tradeActionLogger.log(signal, TradeActionLog.TradeAction.ENTRY_TRIGGERED, ltp, "Entry condition met");
        } catch (Exception e) {
            log.warn("Failed to log trade action: {}", e.getMessage());
        }

        if (dryRun) {
            // Simulate fill at current LTP
            TradeExecution execution = createExecution(signal, ltp, "DRY-RUN-ENTRY-" + signal.getId());
            executionRepository.save(execution);
            signal.setStatus(TradeStatus.ACTIVE);
            signalRepository.save(signal);
            tradeOrchestrationService.onEntryTriggered(signal);
            writeLog(signal, ltp, null, MonitorAction.ENTRY_ORDER_PLACED,
                    "[DRY RUN] Entry simulated at " + ltp, dryRun);
            try {
                tradeActionLogger.log(signal, TradeActionLog.TradeAction.ENTRY_FILLED, ltp, "Filled");
            } catch (Exception e) {
                log.warn("Failed to log trade action: {}", e.getMessage());
            }
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
                return 0.9999;
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
            return 0.9999;
        }
    }

    private boolean isEntryTriggered(TradeSignal signal, BigDecimal ltp, BarSeries series) {
        String pt = signal.getPatternType();
        boolean isDtbOrHns = "DOUBLE_BOTTOM".equals(pt) || "DOUBLE_TOP".equals(pt)
                         || "HNS_BULL".equals(pt) || "HNS_BEAR".equals(pt);
        if (isDtbOrHns) {
            return tryConfirmDtbHnsEntry(signal, series);
        }
        // Existing LTP-touch fallback for other pattern types (TLB, Triangle, Impulse)
        if (signal.getDirection() == TradeDirection.LONG) {
            return ltp.compareTo(signal.getEntryPrice()) >= 0;
        } else {
            return ltp.compareTo(signal.getEntryPrice()) <= 0;
        }
    }

    private boolean tryConfirmDtbHnsEntry(TradeSignal signal, BarSeries series) {
        if (series == null || series.getBarCount() < 1) {
            return false;
        }

        // Step 1: If breakoutLevel not yet found, look for reversal candle in the zone
        if (signal.getBreakoutLevel() == null || signal.getBreakoutLevel().compareTo(BigDecimal.ZERO) <= 0) {
            BigDecimal pivotP1 = signal.getPivotP1();
            BigDecimal pivotP2 = signal.getPivotP2();

            if (pivotP1 == null || pivotP2 == null || pivotP1.compareTo(BigDecimal.ZERO) == 0 || pivotP2.compareTo(BigDecimal.ZERO) == 0) {
                return false;
            }

            String patternType = signal.getPatternType();
            TradeDirection direction = signal.getDirection();

            // Compute retracement zone bounds based on pattern type
            BigDecimal zoneMin, zoneMax;

            if ("DOUBLE_BOTTOM".equals(patternType)) {
                // LONG: zone = [P2 + 0.01×(P1-P2), P2 + 0.39×(P1-P2)]
                BigDecimal diff = pivotP1.subtract(pivotP2);
                zoneMin = pivotP2.add(diff.multiply(BigDecimal.valueOf(0.01)));
                zoneMax = pivotP2.add(diff.multiply(BigDecimal.valueOf(0.39)));
            } else if ("DOUBLE_TOP".equals(patternType)) {
                // SHORT: zone = [P2 - 0.39×(P2-P1), P2 - 0.01×(P2-P1)]
                BigDecimal diff = pivotP2.subtract(pivotP1);
                zoneMin = pivotP2.subtract(diff.multiply(BigDecimal.valueOf(0.39)));
                zoneMax = pivotP2.subtract(diff.multiply(BigDecimal.valueOf(0.01)));
            } else if ("HNS_BEAR".equals(patternType)) {
                // SHORT: zone = [P1 - 0.61×(P1-P2), P1 - 0.23×(P1-P2)]
                BigDecimal diff = pivotP1.subtract(pivotP2);
                zoneMin = pivotP1.subtract(diff.multiply(BigDecimal.valueOf(0.61)));
                zoneMax = pivotP1.subtract(diff.multiply(BigDecimal.valueOf(0.23)));
            } else if ("HNS_BULL".equals(patternType)) {
                // LONG: zone = [P1 + 0.23×(P2-P1), P1 + 0.61×(P2-P1)]
                BigDecimal diff = pivotP2.subtract(pivotP1);
                zoneMin = pivotP1.add(diff.multiply(BigDecimal.valueOf(0.23)));
                zoneMax = pivotP1.add(diff.multiply(BigDecimal.valueOf(0.61)));
            } else {
                // Unknown pattern type
                return false;
            }

            // Walk recent bars and look for reversal candle in zone
            int barCount = series.getBarCount();
            int startIdx = Math.max(0, barCount - 30); // Look at last 30 bars

            for (int i = startIdx; i < barCount; i++) {
                Bar bar = series.getBar(i);
                if (bar.getEndTime().isBefore(signal.getSignalTime())) {
                    continue; // Skip bars before signal creation
                }

                double midpoint = (bar.getHighPrice().doubleValue() + bar.getLowPrice().doubleValue()) / 2.0;

                // Check if candle midpoint is in zone
                if (midpoint >= zoneMin.doubleValue() && midpoint <= zoneMax.doubleValue()) {
                    // Detect reversal candle
                    CandlestickPatternDetector.PatternResult result;
                    if (direction == TradeDirection.LONG) {
                        result = candlestickPatternDetector.detectBullish(series.getBarData(), i);
                    } else {
                        result = candlestickPatternDetector.detectBearish(series.getBarData(), i);
                    }

                    if (result.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                        // Reversal candle found in zone
                        BigDecimal breakoutLevel = BigDecimal.valueOf(result.breakoutLevel());
                        signal.setBreakoutLevel(breakoutLevel);
                        signal.setEntryPrice(breakoutLevel); // Update entryPrice to breakoutLevel
                        signalRepository.save(signal);
                        log.info("[EntryHandler] DTB/HNS reversal candle confirmed for signal {} {} — breakoutLevel={}, waiting for break",
                                signal.getId(), signal.getSymbol(), breakoutLevel);
                        return false; // Candle found but not yet broken
                    }
                }
            }

            return false; // No reversal candle found yet
        }

        // Step 2: breakoutLevel already found, check if latest completed bar crosses it
        int lastIdx = series.getBarCount() - 1;
        if (lastIdx < 0) {
            return false;
        }

        Bar lastBar = series.getBar(lastIdx);
        BigDecimal lastClose = BigDecimal.valueOf(lastBar.getClosePrice().doubleValue());
        BigDecimal breakoutLevel = signal.getBreakoutLevel();

        if (signal.getDirection() == TradeDirection.LONG) {
            if (lastClose.compareTo(breakoutLevel) > 0) {
                log.info("[EntryHandler] DTB/HNS break confirmed for signal {} {} — bar.close={} crossed breakoutLevel={}",
                        signal.getId(), signal.getSymbol(), lastClose, breakoutLevel);
                return true;
            }
        } else {
            if (lastClose.compareTo(breakoutLevel) < 0) {
                log.info("[EntryHandler] DTB/HNS break confirmed for signal {} {} — bar.close={} crossed breakoutLevel={}",
                        signal.getId(), signal.getSymbol(), lastClose, breakoutLevel);
                return true;
            }
        }

        return false;
    }

    private BarSeries getBarSeriesForSignal(TradeSignal signal) {
        try {
            // Parse timeframe string to Interval enum
            String timeframeStr = signal.getTimeframe();
            if (timeframeStr == null) {
                return null;
            }

            Interval interval = Interval.fromUiKey(timeframeStr);
            Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(signal.getSymbol(),
                    new String[]{"NSE", "NFO"});

            if (instrument == null) {
                log.warn("[EntryHandler] Could not find instrument for {}", signal.getSymbol());
                return null;
            }

            return zigZagService.getBarSeries(signal.getSymbol(), instrument, interval);
        } catch (Exception e) {
            log.warn("[EntryHandler] Failed to fetch BarSeries for signal {} {}: {}",
                    signal.getId(), signal.getSymbol(), e.getMessage());
            return null;
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
