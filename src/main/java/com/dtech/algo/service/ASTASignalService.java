package com.dtech.algo.service;

import com.dtech.algo.screener.ScreenerContext;
import com.dtech.algo.screener.ScreenerRegistryService;
import com.dtech.algo.screener.runtime.ScreenerAlertCallback;
import com.dtech.algo.series.IntervalBarSeries;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.HashMap;
import java.util.Map;

/**
 * Adapter service that delegates signal generation to Kotlin screeners (imp_technicals).
 * Fire-and-forget via callback.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ASTASignalService {

    private final ScreenerRegistryService screenerRegistryService;
    private final ScreenerAlertCallback screenerAlertCallback;
    private final AlertQueueService alertQueueService;

    /**
     * Build screener context from timeframe data and invoke 'imp_technicals'.
     * Returns SignalType.None as signals are emitted via callback.
     */
    public SignalType analyzeAndGenerateAlerts(String symbol, Map<TimeframeType, IntervalBarSeries> timeframeData) {
        try {
            IntervalBarSeries wave = timeframeData.get(TimeframeType.WAVE);
            IntervalBarSeries tide = timeframeData.get(TimeframeType.TIDE);

            if (wave == null) {
                log.warn("Missing WAVE series for symbol {}", symbol);
                return SignalType.None;
            }

            Map<String, BarSeries> aliases = new HashMap<>();
            aliases.put("wave", wave);
            if (tide != null) {
                aliases.put("tide", tide);
            }

            String timeframe = wave.getInterval() != null ? wave.getInterval().name() : "UNKNOWN";
            int nowIndex = wave.getEndIndex();

            ScreenerContext ctx = ScreenerContext.builder()
                    .aliases(aliases)
                    .params(Map.of("symbol", symbol, "timeframe", timeframe))
                    .nowIndex(nowIndex)
                    .symbol(symbol)
                    .timeframe(timeframe)
                    .build();

            screenerRegistryService.run("imp_technicals", ctx, screenerAlertCallback);
        } catch (Exception e) {
            log.error("Error running imp_technicals for symbol {}: {}", symbol, e.getMessage(), e);
        }
        return SignalType.None;
    }

    /**
     * Process all pending alerts
     */
    public void processAllAlerts() {
        alertQueueService.processAllAlerts();
    }

    /**
     * Get alert statistics
     */
    public Map<AlertQueueService.AlertType, AlertQueueService.AlertStats> getAlertStatistics() {
        return alertQueueService.getAllStatistics();
    }
}
