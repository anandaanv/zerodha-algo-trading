package com.dtech.algo.screener.runtime;

import com.dtech.algo.screener.ScreenerService;
import com.dtech.algo.screener.db.ScreenerRunEntity;
import com.dtech.algo.screener.db.ScreenerRunRepository;
import com.dtech.algo.screener.enums.SchedulingStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Periodically polls ScreenerRun records and executes them via ScreenerService.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScreenerRunnerService {

    private final ScreenerRunRepository screenerRunRepository;
    private final ScreenerService screenerService;

    /**
     * Polls for scheduled runs and executes them.
     *
     * The cadence can be tuned via properties:
     *  - screener.runner.initial-delay (default 5s)
     *  - screener.runner.fixed-delay   (default 15s)
     */
    @Scheduled(
            initialDelayString = "${screener.runner.initial-delay:5000}",
            fixedDelayString = "${screener.runner.fixed-delay:15000}"
    )
    public void tick() {
        java.time.Instant now = java.time.Instant.now();
        List<ScreenerRunEntity> scheduledRuns =
                screenerRunRepository.findTop200BySchedulingStatusAndExecuteAtLessThanEqualOrderByExecuteAtAsc(
                        SchedulingStatus.SCHEDULED, now);

        if (scheduledRuns.isEmpty()) {
            return;
        }

        for (ScreenerRunEntity run : scheduledRuns) {
            Long runId = run.getId();
            try {
                // Mark as RUNNING first to avoid duplicate concurrent execution
                run.setSchedulingStatus(SchedulingStatus.RUNNING);
                screenerRunRepository.save(run);

                // Execute the screener using defaults for nowIndex and timeframe
                long screenerId = run.getScreenerId();
                String symbol = run.getSymbol();
                int nowIndex = 0; // default meta indicator, can be adapted later
                String timeframe = run.getTimeframe(); // use run's scheduled timeframe

                log.info("Starting ScreenerRun id={} screenerId={} symbol={}", runId, screenerId, symbol);
                screenerService.run(screenerId, symbol, nowIndex, timeframe, null, runId);

                // Mark complete on success
                run.setSchedulingStatus(SchedulingStatus.COMPLETE);
                screenerRunRepository.save(run);
                log.info("Completed ScreenerRun id={}", runId);
            } catch (Exception ex) {
                log.error("Failed ScreenerRun id=" + runId + " with error: " + ex.getMessage(), ex);
                try {
                    run.setSchedulingStatus(SchedulingStatus.FAILED);
                    screenerRunRepository.save(run);
                } catch (Exception e2) {
                    log.error("Error updating ScreenerRun status to FAILED for id=" + runId, e2);
                }
            }
        }
    }
}
