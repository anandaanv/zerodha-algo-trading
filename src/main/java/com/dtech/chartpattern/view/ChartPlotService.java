package com.dtech.chartpattern.view;

import com.dtech.algo.controller.dto.TradingViewChartRequest;
import com.dtech.algo.controller.dto.TradingViewChartRequest.OverlayLevels;
import com.dtech.algo.controller.dto.TradingViewChartRequest.TrendLine;
import com.dtech.algo.controller.dto.TradingViewChartResponse;
import com.dtech.algo.series.Interval;
import com.dtech.algo.service.TradingViewChartService;
import com.dtech.chartpattern.trendline.TrendlineService;
import com.dtech.chartpattern.trendline.TrendlineState;
import com.dtech.chartpattern.trendline.ActiveTrendlineService;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class ChartPlotService {

    private final CandleRepository candleRepository;
    private final ZigZagService zigZagService;
    private final TrendlineService trendlineService;
    private final TradingViewChartService tradingViewChartService;
    private final com.dtech.chartpattern.patterns.PatternTrendlineService patternTrendlineService;
    private final ActiveTrendlineService activeTrendlineService;

    @Value("${charts.visibleBars.default:1000}")
    private int defaultVisibleBars;

    @Value("${patterns.trendlines.enabled:false}")
    private boolean patternTrendlinesEnabled;

    public byte[] renderZigZagChart(String tradingSymbol, Instrument instrument, Interval interval, int width, int height) {
        int candleCount = Math.min(
                candleRepository.findAllByInstrumentAndTimeframe(instrument, interval).size(),
                defaultVisibleBars
        );

        //         ZigZag as segments
        List<TradingViewChartRequest.TrendLine> trendLines = new ArrayList<>();
        var pivots = zigZagService.getOrComputePivots(tradingSymbol, instrument, interval);
        for (int i = 0; i < pivots.size() - 1; i++) {
            var a = pivots.get(i);
            var b = pivots.get(i + 1);
            long aTs = a.getTimestamp().atZone(ZoneId.systemDefault()).toEpochSecond();
            long bTs = b.getTimestamp().atZone(ZoneId.systemDefault()).toEpochSecond();
            String color = b.getValue() >= a.getValue() ? "#2ca02c" : "#ff7f0e";
            trendLines.add(TradingViewChartRequest.TrendLine.builder()
                    .startTs(aTs).startPrice(a.getValue())
                    .endTs(bTs).endPrice(b.getValue())
                    .label("zigzag").color(color).build());
        }
        Map<String, TradingViewChartRequest.OverlayLevels> overlays = new HashMap<>();
        overlays.put(interval.name(), TradingViewChartRequest.OverlayLevels.builder()
                .trendlines(trendLines)
                .build());

        TradingViewChartRequest req = TradingViewChartRequest.builder()
                .symbol(tradingSymbol)
                .timeframes(Collections.singletonList(interval))
                .candleCount(candleCount)
                .layout("1x1")
                .showVolume(true)
                .title(tradingSymbol + " - " + interval.name() + " (ZigZag)")
                .overlays(overlays)
                .build();

        TradingViewChartResponse resp = tradingViewChartService.generateTradingViewCharts(req);
        return resolveImageBytes(resp);
    }

    public byte[] renderTrendlinesChart(String tradingSymbol, Instrument instrument, Interval interval, int width, int height) {
        int candleCount = Math.min(
                candleRepository.findAllByInstrumentAndTimeframe(instrument, interval).size(),
                defaultVisibleBars
        );

        // Build overlays keyed by timeframe (include both trendlines and zigzag-as-segments)
        Map<String, TradingViewChartRequest.OverlayLevels> overlays = new HashMap<>();
        List<TradingViewChartRequest.TrendLine> trendLines = new ArrayList<>();

        var candles = candleRepository.findAllByInstrumentAndTimeframe(instrument, interval);
        candles.sort(Comparator.comparing(com.dtech.kitecon.data.Candle::getTimestamp));
        int lastIdx = candles.size() - 1;
        if (lastIdx < 1) {
            return new byte[0];
        }
        long lastTs = candles.get(lastIdx).getTimestamp().atZone(ZoneId.systemDefault()).toEpochSecond();

        // Trendlines from DB (classic) or pattern-derived lines
//        if (patternTrendlinesEnabled) {
//            var plines = patternTrendlineService.getOrRecalc(tradingSymbol, instrument, interval);
//            for (var pl : plines) {
//                long startTs = pl.getStartTs().atZone(ZoneId.systemDefault()).toEpochSecond();
//                long endTs = pl.getEndTs().atZone(ZoneId.systemDefault()).toEpochSecond();
//                String color =
//                        (pl.getSide().name().equalsIgnoreCase("SUPPORT") || pl.getSide().name().equalsIgnoreCase("LOWER"))
//                                ? "#2ca02c" : "#ff7f0e";
//                String label = pl.getPatternType().name().toLowerCase();
//
//                trendLines.add(TradingViewChartRequest.TrendLine.builder()
//                        .startTs(startTs)
//                        .startPrice(pl.getY1())
//                        .endTs(endTs)
//                        .endPrice(pl.getY2())
//                        .label(label)
//                        .color(color)
//                        .build());
//            }
//        } else {
//            var lines = trendlineService.getOrRecalc(tradingSymbol, interval);
//            for (var tl : lines) {
//                int startIdx = (int) Math.max(0, Math.min(lastIdx, tl.getStartSeq()));
//                long startTs = candles.get(startIdx).getTimestamp().atZone(ZoneId.systemDefault()).toEpochSecond();
//
//                double y1 = tl.getSlopePerBar() * startIdx + tl.getIntercept();
//                double y2 = tl.getSlopePerBar() * lastIdx + tl.getIntercept();
//
//                String color = tl.getSide().name().equalsIgnoreCase("SUPPORT") ? "#2ca02c" : "#ff7f0e";
//                String label = tl.getState() == TrendlineState.RETESTING ? "retesting" :
//                        (tl.getState() == TrendlineState.BROKEN ? "broken" : "active");
//
//                trendLines.add(TradingViewChartRequest.TrendLine.builder()
//                        .startTs(startTs)
//                        .startPrice(y1)
//                        .endTs(lastTs)
//                        .endPrice(y2)
//                        .label(label)
//                        .color(color)
//                        .build());
//            }
//        }

        // Add active trendlines overlays (computed from pivots + ATR)
        try {
            var activeLines = activeTrendlineService.compute(tradingSymbol, instrument, interval);
            for (var al : activeLines) {
                long startTs = al.getPivot1Seq();
                double y1 = al.getSlope() * startTs + al.getIntercept();
                double y2 = al.getSlope() * lastTs + al.getIntercept();

                String color = (al.getSide() == com.dtech.chartpattern.trendline.ActiveTrendlineService.ActiveTrendline.Side.SUPPORT) ? "#2ca02c" : "#ff7f0e";
                // Build informative label: side, score, touches, breaks, extra flags
                StringBuilder lbl = new StringBuilder();
                lbl.append("active-").append(al.getSide().name().toLowerCase())
                   .append(" s:").append(String.format(Locale.US, "%.2f", al.getScore()))
                   .append(" t:").append(al.getTouchCount())
                   .append(" b:").append(al.getBreakCount());
                if (al.isNearFlat()) lbl.append(" flat");
                if (al.getPairType() != null) lbl.append(" ").append(al.getPairType().toLowerCase());

                trendLines.add(TradingViewChartRequest.TrendLine.builder()
                        .startTs(startTs)
                        .startPrice(y1)
                        .endTs(lastTs)
                        .endPrice(y2)
                        .label(lbl.toString())
                        .color(color)
                        .build());
            }
        } catch (Exception e) {
            // Don't fail chart generation if active lines fail
        }

        overlays.put(interval.name(), TradingViewChartRequest.OverlayLevels.builder()
                .trendlines(trendLines)
                .build());

        TradingViewChartRequest req = TradingViewChartRequest.builder()
                .symbol(tradingSymbol)
                .timeframes(Collections.singletonList(interval))
                .candleCount(candleCount)
                .layout("1x1")
                .showVolume(true)
                .title(tradingSymbol + " - " + interval.name() + " (Trendlines)")
                .overlays(overlays)
                .build();

        TradingViewChartResponse resp = tradingViewChartService.generateTradingViewCharts(req);
        return resolveImageBytes(resp);
    }

    private byte[] resolveImageBytes(TradingViewChartResponse resp) {
        try {
            if (resp.getBase64Image() != null && !resp.getBase64Image().isBlank()) {
                return java.util.Base64.getDecoder().decode(resp.getBase64Image());
            }
            if (resp.getChartUrl() != null && !resp.getChartUrl().isBlank()) {
                String fileName = resp.getChartUrl().substring(resp.getChartUrl().lastIndexOf("/") + 1);
                String filePath = tradingViewChartService.getChartsTempDirectory() + "/" + fileName;
                File f = new File(filePath);
                return Files.readAllBytes(f.toPath());
            }
            throw new IllegalStateException("TradingView chart response contained no image");
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve TradingView chart image", e);
        }
    }
}
