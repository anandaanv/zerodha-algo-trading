package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.entity.TradeExecution;
import com.dtech.kitecon.trade.entity.TradeMonitorLog;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.ExitReason;
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
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Handles active trade monitoring and exit decisions.
 *
 * Every 5-min tick for ACTIVE signals:
 *   1. Fetch LTP
 *   2. Check stop loss breach
 *   3. Check target hit
 *   4. TODO: Check candlestick reversal on latest candle (hook ready, logic pending)
 *   5. If no exit: log unrealised P&L
 *   6. If exit triggered: in dry run → simulate fill, in live → place exit order
 *
 * EXIT_PENDING:
 *   - In dry run: no-op (dry run exits are synchronous in handle())
 *   - In live: poll broker order status until filled → COMPLETED
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeExitHandler {

    private static final BigDecimal BROKERAGE_PER_TRADE = BigDecimal.valueOf(1500);

    @Value("${trade.monitor.dryrun:true}")
    private boolean dryRun;

    private final BrokerOrderService brokerOrderService;
    private final TradeSignalRepository signalRepository;
    private final TradeExecutionRepository executionRepository;
    private final TradeMonitorLogRepository logRepository;
    private final TradeOrchestrationService tradeOrchestrationService;
    private final RlExitClient rlExitClient;

    @Transactional
    public void handle(TradeSignal signal, boolean dryRun) {
        BigDecimal ltp = brokerOrderService.fetchLtp(signal.getSymbol(), signal.getInstrumentToken());
        if (ltp == null) {
            writeLog(signal, null, null, null, MonitorAction.NONE, "LTP unavailable — skipping tick", dryRun);
            return;
        }

        TradeExecution execution = executionRepository.findBySignal(signal).orElse(null);
        if (execution == null) {
            writeLog(signal, ltp, null, null, MonitorAction.ERROR, "No execution record found for ACTIVE signal", dryRun);
            return;
        }

        ExitReason exitReason = resolveExitReason(signal, ltp);

        // RL exit optimizer check — only if no hard SL/target exit
        if (exitReason == null) {
            try {
                BigDecimal entry = execution.getEntryPriceActual();
                if (entry != null && entry.compareTo(BigDecimal.ZERO) > 0) {
                    boolean isLong = signal.getDirection() == TradeDirection.LONG;
                    String rlAction = rlExitClient.predict(
                            entry.doubleValue(),
                            signal.getStopLoss().doubleValue(),
                            signal.getTarget().doubleValue(),
                            isLong ? "LONG" : "SHORT",
                            ltp.doubleValue(), ltp.doubleValue(), ltp.doubleValue(),
                            ltp.doubleValue(), 0, 0,
                            0, 0,
                            signal.getStochRsiK() != null ? signal.getStochRsiK().doubleValue() : 50,
                            0, 0, 0.5, 0.05, 0.5, 50,
                            signal.getRrRatio() != null ? signal.getRrRatio().doubleValue() : 2.0,
                            0, 0, 0, 0, 0, 0, 0.5, 0.05, 0, 0, 0, 1.0);
                    if ("EXIT".equals(rlAction)) {
                        exitReason = ExitReason.REVERSAL_CANDLE; // reuse existing enum for RL exit
                        log.info("[ExitHandler] RL agent recommends EXIT for signal {} {}", signal.getId(), signal.getSymbol());
                    }
                }
            } catch (Exception e) {
                log.debug("[ExitHandler] RL exit check failed for signal {}: {}", signal.getId(), e.getMessage());
            }
        }

        if (exitReason != null) {
            triggerExit(signal, execution, ltp, exitReason, dryRun);
        } else {
            BigDecimal unrealisedPnl = calculateUnrealisedPnl(signal, execution, ltp);
            BigDecimal unrealisedPct = execution.getMarginDeployed() != null && execution.getMarginDeployed().compareTo(BigDecimal.ZERO) > 0
                    ? unrealisedPnl.divide(execution.getMarginDeployed(), 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                    : BigDecimal.ZERO;
            String msg = String.format("Monitoring: LTP=%.4f SL=%.4f T=%.4f unrealisedPnL=%.2f (%.2f%%)",
                    ltp.doubleValue(),
                    signal.getStopLoss().doubleValue(),
                    signal.getTarget().doubleValue(),
                    unrealisedPnl.doubleValue(),
                    unrealisedPct.doubleValue());
            writeLog(signal, ltp, unrealisedPnl, unrealisedPct, MonitorAction.NONE, msg, dryRun);
        }
    }

    @Transactional
    public void checkFill(TradeSignal signal, boolean dryRun) {
        if (dryRun) {
            // In dry run, exits are handled synchronously in handle() — this state should not occur
            log.warn("[ExitHandler] Signal {} in EXIT_PENDING during dry run — completing immediately", signal.getId());
            TradeExecution execution = executionRepository.findBySignal(signal).orElse(null);
            if (execution != null) {
                finalise(signal, execution);
            }
            return;
        }
        // TODO: check broker exit order fill status
        // if (brokerOrderService.isOrderFilled(execution.getExitOrderId())) {
        //     BigDecimal fillPrice = brokerOrderService.getFillPrice(execution.getExitOrderId());
        //     execution.setExitPriceActual(fillPrice);
        //     calculateAndSetPnl(signal, execution, fillPrice);
        //     executionRepository.save(execution);
        //     finalise(signal, execution);
        // }
        log.info("[ExitHandler] checkFill for signal {} — live order status check not yet implemented", signal.getId());
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private ExitReason resolveExitReason(TradeSignal signal, BigDecimal ltp) {
        if (signal.getDirection() == TradeDirection.LONG) {
            if (ltp.compareTo(signal.getStopLoss()) <= 0) return ExitReason.STOP_HIT;
            if (ltp.compareTo(signal.getTarget())   >= 0) return ExitReason.TARGET_HIT;
        } else {
            if (ltp.compareTo(signal.getStopLoss()) >= 0) return ExitReason.STOP_HIT;
            if (ltp.compareTo(signal.getTarget())   <= 0) return ExitReason.TARGET_HIT;
        }
        return null;
    }

    private void triggerExit(TradeSignal signal, TradeExecution execution,
                             BigDecimal ltp, ExitReason reason, boolean dryRun) {
        log.info("[ExitHandler] Exit triggered for signal {} {} reason={} at LTP={}",
                signal.getId(), signal.getSymbol(), reason, ltp);

        if (dryRun) {
            execution.setExitPriceActual(ltp);
            execution.setExitTime(Instant.now());
            execution.setExitReason(reason);
            execution.setExitOrderId("DRY-RUN-EXIT-" + signal.getId());
            calculateAndSetPnl(signal, execution, ltp);
            executionRepository.save(execution);
            finalise(signal, execution);
            tradeOrchestrationService.onExitTriggered(signal, reason);
            writeLog(signal, ltp, execution.getNetPnlInr(), execution.getNetPnlPct(),
                    MonitorAction.EXIT_ORDER_PLACED,
                    String.format("[DRY RUN] Exit at %.4f reason=%s netPnL=%.2f",
                            ltp.doubleValue(), reason, execution.getNetPnlInr().doubleValue()),
                    dryRun);
        } else {
            String orderId = brokerOrderService.placeExitOrder(signal, execution);
            execution.setExitOrderId(orderId);
            execution.setExitReason(reason);
            executionRepository.save(execution);
            signal.setStatus(TradeStatus.EXIT_PENDING);
            signalRepository.save(signal);
            writeLog(signal, ltp, null, null, MonitorAction.EXIT_ORDER_PLACED,
                    "Exit order placed: " + orderId + " reason=" + reason, dryRun);
        }
    }

    private void finalise(TradeSignal signal, TradeExecution execution) {
        signal.setStatus(TradeStatus.COMPLETED);
        signalRepository.save(signal);
        log.info("[ExitHandler] Signal {} COMPLETED — netPnL={} reason={}",
                signal.getId(), execution.getNetPnlInr(), execution.getExitReason());
    }

    private void calculateAndSetPnl(TradeSignal signal, TradeExecution execution, BigDecimal exitPrice) {
        BigDecimal entry    = execution.getEntryPriceActual();
        BigDecimal notional = execution.getNotionalValue();
        if (entry == null || notional == null) return;

        BigDecimal movePct = exitPrice.subtract(entry)
                .divide(entry, 8, RoundingMode.HALF_UP);
        if (signal.getDirection() == TradeDirection.SHORT) movePct = movePct.negate();

        BigDecimal grossPnl = notional.multiply(movePct).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netPnl   = grossPnl.subtract(BROKERAGE_PER_TRADE).setScale(2, RoundingMode.HALF_UP);

        BigDecimal margin   = execution.getMarginDeployed();
        BigDecimal pnlPct   = (margin != null && margin.compareTo(BigDecimal.ZERO) > 0)
                ? netPnl.divide(margin, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100))
                : BigDecimal.ZERO;

        execution.setGrossPnlInr(grossPnl);
        execution.setBrokerageInr(BROKERAGE_PER_TRADE);
        execution.setNetPnlInr(netPnl);
        execution.setNetPnlPct(pnlPct);
    }

    private BigDecimal calculateUnrealisedPnl(TradeSignal signal, TradeExecution execution, BigDecimal ltp) {
        BigDecimal entry    = execution.getEntryPriceActual();
        BigDecimal notional = execution.getNotionalValue();
        if (entry == null || notional == null) return BigDecimal.ZERO;
        BigDecimal movePct = ltp.subtract(entry).divide(entry, 8, RoundingMode.HALF_UP);
        if (signal.getDirection() == TradeDirection.SHORT) movePct = movePct.negate();
        return notional.multiply(movePct).setScale(2, RoundingMode.HALF_UP);
    }

    private void writeLog(TradeSignal signal, BigDecimal price, BigDecimal unrealisedPnl,
                          BigDecimal unrealisedPct, MonitorAction action, String detail, boolean dryRun) {
        logRepository.save(TradeMonitorLog.builder()
                .signal(signal)
                .checkedAt(Instant.now())
                .currentPrice(price)
                .unrealisedPnlInr(unrealisedPnl)
                .unrealisedPnlPct(unrealisedPct)
                .actionTaken(action)
                .actionDetail(detail)
                .dryRun(dryRun)
                .workerVersion("1.0.0")
                .build());
    }
}
