package com.dtech.wavelab.service;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.dtech.kitecon.service.copilot.dto.TrendSegment;
import com.dtech.wavelab.entity.WlOverlay;
import com.dtech.wavelab.repo.WlWatchlistItemRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class WaveLabAnalysisService {

    private final ZigZagService zigZagService;
    private final WaveLabOverlayService overlayService;
    private final InstrumentRepository instrumentRepository;
    private final WlWatchlistItemRepository watchlistItemRepository;
    private final MarketStructureService marketStructureService;

    @Transactional
    public List<WlOverlay> analyze(String symbol, String timeframe) {
        // Clear existing overlays for this symbol+timeframe
        overlayService.clearOverlays(symbol, timeframe);

        // Resolve Interval from UI key (e.g., "15m" -> FifteenMinute)
        Interval interval = Interval.fromUiKey(timeframe);

        // Lookup instrument - try NSE first, fallback to BSE
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"BSE"});
        }
        if (instrument == null) {
            throw new IllegalArgumentException("Instrument not found: " + symbol);
        }

        List<WlOverlay> result = new ArrayList<>();

        try {
            // Build BarSeries from DB candles
            BarSeries series = zigZagService.getBarSeries(symbol, instrument, interval);

            if (series.isEmpty()) {
                log.warn("[WaveLab] No candle data for {} {}", symbol, timeframe);
                return result;
            }

            // --- ZigZag (input pivots for market-structure analysis) ---
            ZigZagParams params = zigZagService.resolveParams(symbol, interval);
            List<ZigZagPoint> pivots = zigZagService.detect(series, params);
            log.info("[WaveLab] ZigZag: {} pivots for {} {}", pivots.size(), symbol, timeframe);

            // --- Market Structure trend layer ---
            MarketStructureData structure = marketStructureService.analyse(pivots, timeframe);
            log.info("[WaveLab] MarketStructure: {} swing points for {} {}", structure.getSwingPoints().size(), symbol, timeframe);

            List<TrendSegment> segments = structure.getTrendSegments() == null ? List.of() : structure.getTrendSegments();
            long fallbackEndEpoch = structure.getSwingPoints().isEmpty()
                    ? series.getLastBar().getEndTime().getEpochSecond()
                    : structure.getSwingPoints().get(structure.getSwingPoints().size() - 1).getTimestamp().getEpochSecond();

            Set<String> labelledPoints = new HashSet<>();
            for (TrendSegment seg : segments) {
                if (seg.getStartTime() == null) {
                    continue;
                }

                long startEpoch = seg.getStartTime().getEpochSecond();
                long endEpoch = seg.getEndTime() != null ? seg.getEndTime().getEpochSecond() : fallbackEndEpoch;
                String lineColor = seg.getDirection() == TrendSegment.Direction.UPTREND ? "#26a69a" : "#ef5350";

                // Draw one line per trend segment (instead of per-zigzag leg)
                String lineJson = buildLineJson(startEpoch, seg.getStartPrice(), endEpoch, seg.getEndPrice(), lineColor, "trend_high_low");
                result.add(overlayService.saveOverlay(symbol, timeframe, "trend_segments", lineJson));

                // Mark only trend endpoints
                if (seg.getDirection() == TrendSegment.Direction.UPTREND) {
                    saveTrendLabelIfAbsent(result, labelledPoints, symbol, timeframe, startEpoch, seg.getStartPrice(), "TREND_LOW", "#26a69a");
                    saveTrendLabelIfAbsent(result, labelledPoints, symbol, timeframe, endEpoch, seg.getEndPrice(), "TREND_HIGH", "#ef5350");
                } else {
                    saveTrendLabelIfAbsent(result, labelledPoints, symbol, timeframe, startEpoch, seg.getStartPrice(), "TREND_HIGH", "#ef5350");
                    saveTrendLabelIfAbsent(result, labelledPoints, symbol, timeframe, endEpoch, seg.getEndPrice(), "TREND_LOW", "#26a69a");
                }
            }

        } catch (Exception e) {
            log.error("[WaveLab] Analysis failed for {} {}: {}", symbol, timeframe, e.getMessage(), e);
            throw e;
        }

        log.info("[WaveLab] Analysis complete: {} overlays saved for {} {}", result.size(), symbol, timeframe);
        return result;
    }

    private void saveTrendLabelIfAbsent(List<WlOverlay> result,
                                        Set<String> labelledPoints,
                                        String symbol,
                                        String timeframe,
                                        long epochSeconds,
                                        double price,
                                        String label,
                                        String color) {
        String key = epochSeconds + "|" + String.format(Locale.US, "%.4f", price) + "|" + label;
        if (!labelledPoints.add(key)) {
            return;
        }
        String json = buildLabelJson(epochSeconds, price, color, label);
        result.add(overlayService.saveOverlay(symbol, timeframe, "trend_segments", json));
    }

    private String buildLineJson(long time1, double price1, long time2, double price2, String color, String label) {
        return String.format(Locale.US,
            "{\"type\":\"trend_line\",\"points\":[{\"time\":%d,\"price\":%.4f},{\"time\":%d,\"price\":%.4f}],\"color\":\"%s\",\"label\":\"%s\"}",
            time1, price1, time2, price2, color, label);
    }

    private String buildLabelJson(long time, double price, String color, String label) {
        return String.format(Locale.US,
            "{\"type\":\"label\",\"points\":[{\"time\":%d,\"price\":%.4f}],\"color\":\"%s\",\"label\":\"%s\"}",
            time, price, color, label);
    }

    public BatchResult analyzeAll(Long watchlistId, String timeframe) {
        List<com.dtech.wavelab.entity.WlWatchlistItem> items =
            watchlistItemRepository.findByWatchlistIdOrderByDisplayOrderAsc(watchlistId);
        int succeeded = 0, failed = 0;
        List<String> errors = new ArrayList<>();
        for (com.dtech.wavelab.entity.WlWatchlistItem item : items) {
            try {
                analyze(item.getSymbol(), timeframe);
                succeeded++;
            } catch (Exception e) {
                failed++;
                errors.add(item.getSymbol() + ": " + e.getMessage());
                log.warn("[WaveLab] analyzeAll failed for {}: {}", item.getSymbol(), e.getMessage());
            }
        }
        log.info("[WaveLab] analyzeAll done: {}/{} succeeded for watchlist {} tf={}", succeeded, items.size(), watchlistId, timeframe);
        return new BatchResult(items.size(), succeeded, failed, errors);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    public static class BatchResult {
        private int total;
        private int succeeded;
        private int failed;
        private List<String> errors;
    }
}
