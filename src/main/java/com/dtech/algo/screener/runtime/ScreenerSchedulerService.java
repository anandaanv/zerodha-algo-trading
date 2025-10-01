package com.dtech.algo.screener.runtime;

import com.dtech.algo.screener.db.ScreenerEntity;
import com.dtech.algo.screener.db.ScreenerRepository;
import com.dtech.algo.screener.db.ScreenerRunEntity;
import com.dtech.algo.screener.db.ScreenerRunRepository;
import com.dtech.algo.screener.enums.SchedulingStatus;
import com.dtech.algo.screener.model.RunConfig;
import com.dtech.algo.screener.model.SchedulingConfig;
import com.dtech.kitecon.repository.IndexSymbolRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScreenerSchedulerService {

    private final IndexSymbolRepository indexSymbolRepository;
    private final ScreenerRepository screenerRepository;
    private final ScreenerRunRepository screenerRunRepository;

    /**
     * Goes through active screeners and schedules runs per SchedulingConfig.
     * Default: run hourly. Override with screener.scheduler.hourly-cron.
     */
    @Scheduled(cron = "${screener.scheduler.hourly-cron:0 0 * * * ?}")
    public void tick() {
        List<ScreenerEntity> screeners = screenerRepository.findByDeletedFalse();
        if (screeners.isEmpty()) {
            return;
        }
        Instant executeAt = Instant.now(); // schedule for immediate execution window

        int created = 0;
        for (ScreenerEntity s : screeners) {
            SchedulingConfig sc = s.getSchedulingConfig();
            if (sc == null || sc.getRunConfigs() == null || sc.getRunConfigs().isEmpty()) {
                continue;
            }
            List<ScreenerRunEntity> batch = new ArrayList<>();
            for (RunConfig rc : sc.getRunConfigs()) {
                String timeframe = rc.getTimeframe();
                if (timeframe == null || timeframe.isBlank()) continue;
                List<String> symbols = getSymbols(rc);
                if (symbols == null || symbols.isEmpty()) continue;

                for (String symbol : symbols) {
                    if (symbol == null || symbol.isBlank()) continue;

                    // Avoid duplicates for the same slot
                    boolean exists = screenerRunRepository.existsByScreenerIdAndSymbolAndTimeframeAndExecuteAt(
                            s.getId(), symbol, timeframe, executeAt);
                    if (exists) continue;

                    ScreenerRunEntity run = ScreenerRunEntity.builder()
                            .screenerId(s.getId())
                            .schedulingStatus(SchedulingStatus.SCHEDULED)
                            .symbol(symbol.trim())
                            .timeframe(timeframe)
                            .executeAt(executeAt)
                            .currentState("scheduled")
                            .build();
                    batch.add(run);
                }
            }
            if (!batch.isEmpty()) {
                screenerRunRepository.saveAll(batch);
                created += batch.size();
            }
        }
        if (created > 0) {
            log.info("ScreenerScheduler: scheduled {} runs at {}", created, executeAt);
        }
    }

    private List<String> getSymbols(RunConfig rc) {
        List<String> symbols = rc.getSymbols();
        List<String> result = new ArrayList<>();

        for (String symbol : symbols) {
            if (symbol != null && symbol.startsWith("INDEX-")) {
                List<String> indexSymbols = resolveIndex(symbol);
                if (indexSymbols != null) {
                    result.addAll(indexSymbols);
                }
            } else {
                result.add(symbol);
            }
        }

        return result;
    }

    private List<String> resolveIndex(String symbol) {
        String indexName = symbol.substring("INDEX-".length()).trim();
        return indexSymbolRepository.findAllSymbolsByIndexName(indexName);
    }
}
