package com.dtech.kitecon.trade.service;

import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Core trade monitoring loop.
 *
 * Runs every 5 minutes (configurable via trade.monitor.interval).
 * Loads all non-terminal signals and routes each through the appropriate handler.
 *
 * DRY RUN mode (trade.monitor.dryrun=true, default):
 *   - Entry and exit decisions are logged but orders are simulated (no real broker calls).
 *   - LTP is read from the latest candle in the database.
 *   - Use this mode for paper trading / forward testing before going live.
 *
 * LIVE mode (trade.monitor.dryrun=false):
 *   - Requires BrokerOrderService to be wired to Kite Connect (see TODOs in BrokerOrderService).
 *   - Will place real orders. Only enable after thorough dry-run validation.
 *
 * Adding signals:
 *   - Inject TradeSignalRepository and save a TradeSignal with status=WATCHING_ENTRY.
 *   - The worker picks it up on the next tick.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TradeMonitorWorker {

    private static final List<TradeStatus> ACTIVE_STATUSES = List.of(
            TradeStatus.WATCHING_ENTRY,
            TradeStatus.ENTRY_PENDING,
            TradeStatus.ACTIVE,
            TradeStatus.EXIT_PENDING
    );

    @Value("${trade.monitor.dryrun:true}")
    private boolean dryRun;

    private final TradeSignalRepository signalRepository;
    private final TradeEntryHandler entryHandler;
    private final TradeExitHandler exitHandler;

    /**
     * Main monitoring loop — runs every 5 minutes by default.
     * Override interval via trade.monitor.interval (milliseconds).
     */
    @Scheduled(fixedDelayString = "${trade.monitor.interval:300000}")
    public void run() {
        List<TradeSignal> signals = signalRepository.findByStatusIn(ACTIVE_STATUSES);

        if (signals.isEmpty()) {
            log.debug("[TradeMonitor] No active signals to monitor");
            return;
        }

        log.info("[TradeMonitor] Tick — {} active signal(s) [dryRun={}]", signals.size(), dryRun);

        int processed = 0, errors = 0;
        for (TradeSignal signal : signals) {
            try {
                dispatch(signal);
                processed++;
            } catch (Exception e) {
                errors++;
                log.error("[TradeMonitor] Error processing signal id={} symbol={}: {}",
                        signal.getId(), signal.getSymbol(), e.getMessage(), e);
            }
        }

        log.info("[TradeMonitor] Tick complete — processed={} errors={}", processed, errors);
    }

    private void dispatch(TradeSignal signal) {
        switch (signal.getStatus()) {
            case WATCHING_ENTRY -> entryHandler.handle(signal, dryRun);
            case ENTRY_PENDING  -> entryHandler.checkFill(signal, dryRun);
            case ACTIVE         -> exitHandler.handle(signal, dryRun);
            case EXIT_PENDING   -> exitHandler.checkFill(signal, dryRun);
            default             -> log.warn("[TradeMonitor] Unexpected status {} for signal {}",
                                            signal.getStatus(), signal.getId());
        }
    }
}
