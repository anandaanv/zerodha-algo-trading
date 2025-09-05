package com.dtech.algo.service;

import com.dtech.algo.controller.dto.ASTAScreenRequest;
import com.dtech.algo.controller.dto.ASTAScreenResponse;
import com.dtech.algo.controller.dto.ASTASignalResult;
import com.dtech.algo.series.IntervalBarSeries;
import com.dtech.kitecon.controller.BarSeriesHelper;
import com.dtech.kitecon.service.DataFetchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Service to screen symbols for ASTA signals, including index expansion.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ASTAScreenService {

    private final BarSeriesHelper barSeriesHelper;
    private final ASTASignalService astaSignalService;
    private final SymbolExpansionService symbolExpansionService;
    private final DataFetchService dataFetchService;

    /**
     * Screen symbols for signals using provided timeframe map.
     * Expands index names to constituent symbols when applicable.
     */
    public ASTAScreenResponse screen(ASTAScreenRequest request) {
        List<ASTASignalResult> results = new ArrayList<>();

        if (request.getSymbols() == null || request.getSymbols().isEmpty()
                || request.getTimeframeMap() == null || request.getTimeframeMap().isEmpty()) {
            log.warn("Invalid screen request: symbols or timeframeMap missing");
            return ASTAScreenResponse.builder().results(results).build();
        }

        for (String symbol : request.getSymbols()) {
            // Expand indices into their constituent symbols if applicable
            List<String> targetSymbols = symbolExpansionService.expandSymbolOrIndex(symbol);

            for (String target : targetSymbols) {
                // Download candle data for all timeframes
                request.getTimeframeMap().values().forEach(interval -> dataFetchService.downloadCandleData(target, interval, new String[]{"NSE"}));
                Map<TimeframeType, IntervalBarSeries> seriesMap =
                        new EnumMap<>(TimeframeType.class);

                // Build per-timeframe series map from request
                for (Map.Entry<TimeframeType, com.dtech.algo.series.Interval> entry
                        : request.getTimeframeMap().entrySet()) {
                    try {
                        IntervalBarSeries series = barSeriesHelper.getIntervalBarSeries(target, entry.getValue().name());
                        if (series != null && series.getBarCount() > 0) {
                            seriesMap.put(entry.getKey(), series);
                        }
                    } catch (Exception e) {
                        log.warn("Failed to load series for {} {}: {}", target, entry.getValue(), e.getMessage());
                    }
                }

                // Analyze signals (pure result; alert queuing handled separately if desired)
                SignalType result = astaSignalService.analyzeAndGenerateAlerts(target, seriesMap);
                results.add(ASTASignalResult.builder()
                        .signals(Collections.singletonList(result))
                        .symbol(target)
                        .build());
            }
        }

        return ASTAScreenResponse.builder().results(results).build();
    }
}
