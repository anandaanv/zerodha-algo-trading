package com.dtech.kitecon.patternscanner;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.backtest.DetectedPattern;
import com.dtech.kitecon.backtest.PatternComboBacktestService;
import com.dtech.kitecon.service.DataFetchService;
import com.dtech.kitecon.backtest.PatternComboBacktestService.DailyIndicators;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.kitecon.simulation.IncrementalZigZag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatternScanService {

    private final PatternComboBacktestService patternComboBacktestService;
    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final DataFetchService dataFetchService;

    private static final List<String> NIFTY50 = List.of(
        "RELIANCE", "TCS", "HDFCBANK", "BHARTIARTL", "ICICIBANK",
        "INFY", "SBIN", "BAJFINANCE", "HINDUNILVR", "ITC",
        "LT", "KOTAKBANK", "AXISBANK", "ASIANPAINT", "HCLTECH",
        "MARUTI", "SUNPHARMA", "TITAN", "ADANIENT", "NTPC",
        "WIPRO", "ULTRACEMCO", "ONGC", "NESTLEIND", "TATAMOTORS",
        "JSWSTEEL", "TATACONSUM", "M&M", "POWERGRID", "COALINDIA",
        "BAJAJFINSV", "TATASTEEL", "TECHM", "BAJAJ-AUTO", "DRREDDY",
        "DIVISLAB", "BRITANNIA", "CIPLA", "APOLLOHOSP", "EICHERMOT",
        "GRASIM", "INDUSINDBK", "BPCL", "HEROMOTOCO", "HINDALCO",
        "SHRIRAMFIN", "LTIM", "SBILIFE", "HDFCLIFE", "TRENT"
    );

    public List<String> getNifty50() {
        return NIFTY50;
    }

    public List<String> getFnoSymbols() {
        // Only return symbols that have a corresponding NSE equity instrument — skips renamed/delisted/index symbols
        return instrumentRepository.findDistinctFutureUnderlyingNamesWithNseEquity()
                .stream()
                .filter(n -> n != null && !n.isBlank())
                .sorted()
                .collect(Collectors.toList());
    }

    public PatternScanResultDto scan(String symbol, Interval watchingTf, Interval confirmTf) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            throw new RuntimeException("Instrument not found: " + symbol);
        }

        // Refresh candle data for this symbol before scanning — incremental fetch from latest to now
        try { dataFetchService.updateInstrumentToLatest(symbol, watchingTf, new String[]{"NSE"}); } catch (Exception e) {
            log.warn("[Scan] Data refresh failed for {} {}: {}", symbol, watchingTf, e.getMessage());
        }
        try { dataFetchService.updateInstrumentToLatest(symbol, confirmTf, new String[]{"NSE"}); } catch (Exception e) {
            log.warn("[Scan] Data refresh failed for {} {}: {}", symbol, confirmTf, e.getMessage());
        }
        try { dataFetchService.updateInstrumentToLatest(symbol, Interval.Day, new String[]{"NSE"}); } catch (Exception e) {
            log.warn("[Scan] Data refresh failed for {} {}: {}", symbol, Interval.Day, e.getMessage());
        }

        BarSeries seriesW = zigZagService.getBarSeries(symbol, instrument, watchingTf);
        if (seriesW == null || seriesW.isEmpty()) {
            throw new RuntimeException("No watching TF data for: " + symbol);
        }

        List<Bar> barsW = new ArrayList<>();
        for (int i = 0; i < seriesW.getBarCount(); i++) barsW.add(seriesW.getBar(i));

        Map<Instant, Integer> tsToIdxW = new HashMap<>();
        for (int i = 0; i < barsW.size(); i++) tsToIdxW.put(barsW.get(i).getEndTime(), i);

        double[] atrArrW = patternComboBacktestService.computeAtrPublic(barsW, 14);
        double[] rsiValuesW = patternComboBacktestService.computeRsiPublic(seriesW, 14);
        double[] macdHistArrW = patternComboBacktestService.computeMacdHistPublic(seriesW);
        double[] stochRsiKW = patternComboBacktestService.computeStochRsiKPublic(rsiValuesW);

        ZigZagParams baseW = zigZagService.resolveParams(symbol, watchingTf);
        ZigZagParams paramsW = ZigZagParams.ofDefaults(baseW.getAtrLength(), baseW.getAtrMult(),
                baseW.getPctMin(), baseW.getHysteresis(), baseW.getMinBarsBetweenPivots(),
                baseW.isDynamicPctEnabled(), baseW.getVolMult(), baseW.getRvolWindow(),
                ZigZagParams.Mode.BACKTEST);
        List<ZigZagPoint> pivotsW = zigZagService.detect(seriesW, paramsW);

        // Build pivots with trailing extreme for DTB/HNS candidate detection
        List<ZigZagPoint> pivotsWithTrailingExtreme = pivotsW;
        try {
            if (!barsW.isEmpty()) {
                IncrementalZigZag izz = new IncrementalZigZag(paramsW);
                for (int i = 0; i < barsW.size(); i++) {
                    izz.processBar(barsW.get(i), i);
                }
                Bar currentBar = barsW.get(barsW.size() - 1);
                pivotsWithTrailingExtreme = izz.getPivotsWithTrailingExtreme(currentBar);
            }
        } catch (Exception e) {
            log.warn("[Scan] Failed to compute pivots with trailing extreme for {}: {}", symbol, e.getMessage());
            pivotsWithTrailingExtreme = pivotsW;
        }

        // Detect all watching patterns
        List<DetectedPattern> watchingPatterns = new ArrayList<>();
        watchingPatterns.addAll(patternComboBacktestService.scanDtbHnsCandidatePublic(pivotsWithTrailingExtreme, seriesW, atrArrW, rsiValuesW, macdHistArrW, stochRsiKW, tsToIdxW));
        watchingPatterns.addAll(patternComboBacktestService.scanTriangleWatchingPublic(pivotsW, barsW, tsToIdxW, atrArrW, rsiValuesW, macdHistArrW, stochRsiKW));
        watchingPatterns.addAll(patternComboBacktestService.scanFlagWatchingPublic(pivotsW, barsW, tsToIdxW, atrArrW, rsiValuesW, macdHistArrW, stochRsiKW));
        watchingPatterns.addAll(patternComboBacktestService.scanTrendlineBreakoutWatchingPublic(pivotsW, barsW, tsToIdxW, atrArrW, rsiValuesW, macdHistArrW, stochRsiKW));

        // Keep only patterns whose last pivot is the most recent ZigZag pivot — anything older is historical
        // Exception: TRENDLINE_BREAKOUT uses breakout bar time, so check if it's within the last N bars
        if (!pivotsW.isEmpty()) {
            Instant latestPivotTime = pivotsW.get(pivotsW.size() - 1).getTimestamp();
            int lastBarIdx = barsW.size() - 1;
            Instant recentCutoff = lastBarIdx >= 5 ? barsW.get(lastBarIdx - 5).getEndTime() : barsW.get(0).getEndTime();
            watchingPatterns.removeIf(p -> {
                if ("TRENDLINE_BREAKOUT".equals(p.getPatternType())) {
                    // For TLB, keep if breakout happened in the last 5 bars
                    return p.getKeyLevelTime().isBefore(recentCutoff);
                }
                return !latestPivotTime.equals(p.getKeyLevelTime());
            });
        }

        // Load daily and confirm series for indicator computation
        BarSeries seriesDaily = null;
        try { seriesDaily = zigZagService.getBarSeries(symbol, instrument, Interval.Day); } catch (Exception ignored) {}
        BarSeries seriesC = null;
        try { seriesC = zigZagService.getBarSeries(symbol, instrument, confirmTf); } catch (Exception ignored) {}

        DailyIndicators dailyInd    = patternComboBacktestService.computeDailyIndicators(seriesDaily);
        DailyIndicators watchingInd = patternComboBacktestService.computeDailyIndicators(seriesW);
        DailyIndicators confirmInd  = patternComboBacktestService.computeDailyIndicators(seriesC);

        // Build pattern DTOs
        List<PatternDto> patternDtos = watchingPatterns.stream().map(p -> PatternDto.builder()
                .patternType(p.getPatternType())
                .bullish(p.isBullish())
                .keyLevel(p.getKeyLevel())
                .keyLevelTime(p.getKeyLevelTime())
                .target(p.getOwnTarget())
                .patternHeight(p.getPatternHeight())
                .atr(p.getAtr())
                .rsiAtP1(p.getRsiAtP1())
                .rsiAtP2(p.getRsiAtP2())
                .p0Time(p.getPivotBTime())
                .p1Time(p.getPivotDTime())
                .macdHistAtP1(p.getMacdHistAtP1())
                .macdHistAtP2(p.getMacdHistAtP2())
                .stochRsiK(p.getStochRsiK())
                .adxWatching(watchingInd.adxAtTs(p.getKeyLevelTime()))
                .adxWatchingEma(watchingInd.adxEmaAtTs(p.getKeyLevelTime()))
                .macdWatching(watchingInd.macdLineAtTs(p.getKeyLevelTime()))
                .macdSignalWatching(watchingInd.macdSignalAtTs(p.getKeyLevelTime()))
                .bbWidthWatching(watchingInd.bbWidthAtTs(p.getKeyLevelTime()))
                .bbPctBWatching(watchingInd.bbPctBAtTs(p.getKeyLevelTime()))
                .adxConfirm(confirmInd.adxAtTs(p.getKeyLevelTime()))
                .adxConfirmEma(confirmInd.adxEmaAtTs(p.getKeyLevelTime()))
                .dailyRsi(dailyInd.rsiAtTs(p.getKeyLevelTime()))
                .dailyAdx(dailyInd.adxAtTs(p.getKeyLevelTime()))
                .dailyAdxEma(dailyInd.adxEmaAtTs(p.getKeyLevelTime()))
                .macdDaily(dailyInd.macdLineAtTs(p.getKeyLevelTime()))
                .macdSignalDaily(dailyInd.macdSignalAtTs(p.getKeyLevelTime()))
                .bbWidthDaily(dailyInd.bbWidthAtTs(p.getKeyLevelTime()))
                .bbPctBDaily(dailyInd.bbPctBAtTs(p.getKeyLevelTime()))
                .bbExpanding(watchingInd.bbExpandingAtTs(p.getKeyLevelTime(), 5))
                .bbAligned(watchingInd.bbAlignedAtTs(p.getKeyLevelTime(), 5, p.isBullish()))
                .rsiSlope(watchingInd.rsiSlopeAtTs(p.getKeyLevelTime(), 5))
                .macdHistSlope(watchingInd.macdHistSlopeAtTs(p.getKeyLevelTime(), 5))
                .adxSlope(watchingInd.adxSlopeAtTs(p.getKeyLevelTime(), 5))
                .pivotP0(p.getPivotP0())
                .pivotP1(p.getPivotP1())
                .pivotP2(p.getPivotP2())
                .pivotP3(p.getPivotP3())
                .breakoutLevel(0.0)
                .build()).toList();

        // Build overlays for watching TF
        Map<String, Object> watchingOverlays = buildOverlays(watchingPatterns, barsW, tsToIdxW);

        // Build confirmation TF overlays (basic OHLC — no patterns, just candles)
        Map<String, Object> confirmOverlays = new HashMap<>();

        return PatternScanResultDto.builder()
                .symbol(symbol)
                .watchingTf(watchingTf.getUiKey())
                .confirmTf(confirmTf.getUiKey())
                .patterns(patternDtos)
                .watchingTfOverlays(watchingOverlays)
                .confirmTfOverlays(confirmOverlays)
                .build();
    }

    public List<PatternDto> scanLenientTrendlinePatterns(String symbol, Interval watchingTf) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            return new ArrayList<>();
        }

        try { dataFetchService.updateInstrumentToLatest(symbol, watchingTf, new String[]{"NSE"}); } catch (Exception e) {
            log.warn("[ScanLenient] Data refresh failed for {} {}: {}", symbol, watchingTf, e.getMessage());
        }

        BarSeries seriesW = zigZagService.getBarSeries(symbol, instrument, watchingTf);
        if (seriesW == null || seriesW.isEmpty()) {
            return new ArrayList<>();
        }

        List<Bar> barsW = new ArrayList<>();
        for (int i = 0; i < seriesW.getBarCount(); i++) barsW.add(seriesW.getBar(i));

        Map<Instant, Integer> tsToIdxW = new HashMap<>();
        for (int i = 0; i < barsW.size(); i++) tsToIdxW.put(barsW.get(i).getEndTime(), i);

        double[] atrArrW = patternComboBacktestService.computeAtrPublic(barsW, 14);
        double[] rsiValuesW = patternComboBacktestService.computeRsiPublic(seriesW, 14);
        double[] macdHistArrW = patternComboBacktestService.computeMacdHistPublic(seriesW);
        double[] stochRsiKW = patternComboBacktestService.computeStochRsiKPublic(rsiValuesW);

        List<DetectedPattern> lenientPatterns = patternComboBacktestService.scanLenientTrendlineWatchingPublic(
                barsW, tsToIdxW, atrArrW, rsiValuesW, macdHistArrW, stochRsiKW);

        BarSeries seriesDaily = null;
        try { seriesDaily = zigZagService.getBarSeries(symbol, instrument, Interval.Day); } catch (Exception ignored) {}

        DailyIndicators dailyInd    = patternComboBacktestService.computeDailyIndicators(seriesDaily);
        DailyIndicators watchingInd = patternComboBacktestService.computeDailyIndicators(seriesW);

        return lenientPatterns.stream().map(p -> PatternDto.builder()
                .patternType(p.getPatternType())
                .bullish(p.isBullish())
                .keyLevel(p.getKeyLevel())
                .keyLevelTime(p.getKeyLevelTime())
                .target(p.getOwnTarget())
                .patternHeight(p.getPatternHeight())
                .atr(p.getAtr())
                .rsiAtP1(p.getRsiAtP1())
                .rsiAtP2(p.getRsiAtP2())
                .p0Time(p.getPivotBTime())
                .p1Time(p.getPivotDTime())
                .macdHistAtP1(p.getMacdHistAtP1())
                .macdHistAtP2(p.getMacdHistAtP2())
                .stochRsiK(p.getStochRsiK())
                .adxWatching(watchingInd.adxAtTs(p.getKeyLevelTime()))
                .adxWatchingEma(watchingInd.adxEmaAtTs(p.getKeyLevelTime()))
                .macdWatching(watchingInd.macdLineAtTs(p.getKeyLevelTime()))
                .macdSignalWatching(watchingInd.macdSignalAtTs(p.getKeyLevelTime()))
                .bbWidthWatching(watchingInd.bbWidthAtTs(p.getKeyLevelTime()))
                .bbPctBWatching(watchingInd.bbPctBAtTs(p.getKeyLevelTime()))
                .adxConfirm(0)
                .adxConfirmEma(0)
                .dailyRsi(dailyInd.rsiAtTs(p.getKeyLevelTime()))
                .dailyAdx(dailyInd.adxAtTs(p.getKeyLevelTime()))
                .dailyAdxEma(dailyInd.adxEmaAtTs(p.getKeyLevelTime()))
                .macdDaily(dailyInd.macdLineAtTs(p.getKeyLevelTime()))
                .macdSignalDaily(dailyInd.macdSignalAtTs(p.getKeyLevelTime()))
                .bbWidthDaily(dailyInd.bbWidthAtTs(p.getKeyLevelTime()))
                .bbPctBDaily(dailyInd.bbPctBAtTs(p.getKeyLevelTime()))
                .bbExpanding(watchingInd.bbExpandingAtTs(p.getKeyLevelTime(), 5))
                .bbAligned(watchingInd.bbAlignedAtTs(p.getKeyLevelTime(), 5, p.isBullish()))
                .rsiSlope(watchingInd.rsiSlopeAtTs(p.getKeyLevelTime(), 5))
                .macdHistSlope(watchingInd.macdHistSlopeAtTs(p.getKeyLevelTime(), 5))
                .adxSlope(watchingInd.adxSlopeAtTs(p.getKeyLevelTime(), 5))
                .build()).toList();
    }

    /**
     * Computes live indicator values for a signal's symbol at the time of entry.
     * Used by TradeEntryHandler to score the ML filter with fresh market data.
     */
    public PatternDto computeCurrentIndicators(TradeSignal signal) {
        try {
            String tfStr = signal.getTimeframe() != null ? signal.getTimeframe() : "1h";
            Interval watchingTf = switch (tfStr.toLowerCase()) {
                case "1d", "day", "d" -> Interval.Day;
                case "15m", "15min"   -> Interval.FifteenMinute;
                case "5m",  "5min"    -> Interval.FiveMinute;
                default               -> Interval.OneHour;
            };
            Interval confirmTf = Interval.FifteenMinute;

            Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                    signal.getSymbol(), new String[]{"NSE", "NFO"});
            if (instrument == null) return null;

            BarSeries seriesW = null;
            BarSeries seriesDaily = null;
            BarSeries seriesC = null;
            try { seriesW = zigZagService.getBarSeries(signal.getSymbol(), instrument, watchingTf); } catch (Exception ignored) {}
            try { seriesDaily = zigZagService.getBarSeries(signal.getSymbol(), instrument, Interval.Day); } catch (Exception ignored) {}
            try { seriesC = zigZagService.getBarSeries(signal.getSymbol(), instrument, confirmTf); } catch (Exception ignored) {}

            DailyIndicators watchingInd = patternComboBacktestService.computeDailyIndicators(seriesW);
            DailyIndicators dailyInd    = patternComboBacktestService.computeDailyIndicators(seriesDaily);
            DailyIndicators confirmInd  = patternComboBacktestService.computeDailyIndicators(seriesC);

            double currentRsi = 0.0, currentMacdHist = 0.0, currentStochRsiK = 0.0;
            if (seriesW != null && !seriesW.isEmpty()) {
                double[] rsiArr      = patternComboBacktestService.computeRsiPublic(seriesW, 14);
                double[] stochRsiArr = patternComboBacktestService.computeStochRsiKPublic(rsiArr);
                double[] macdHistArr = patternComboBacktestService.computeMacdHistPublic(seriesW);
                int last = seriesW.getBarCount() - 1;
                currentRsi       = rsiArr[last];
                currentMacdHist  = macdHistArr[last];
                currentStochRsiK = stochRsiArr[last];
            }

            Instant now = Instant.now();
            return PatternDto.builder()
                    .patternType(signal.getPatternType())
                    .rrRatio(signal.getRrRatio() != null ? signal.getRrRatio().doubleValue() : 0.0)
                    .patternHeight(signal.getPatternHeight() != null ? signal.getPatternHeight().doubleValue() : 0.0)
                    .rsiAtP1(currentRsi)
                    .rsiAtP2(currentRsi)
                    .macdHistAtP1(currentMacdHist)
                    .macdHistAtP2(currentMacdHist)
                    .stochRsiK(currentStochRsiK)
                    .adxWatching(watchingInd.adxAtTs(now))
                    .adxWatchingEma(watchingInd.adxEmaAtTs(now))
                    .macdWatching(watchingInd.macdLineAtTs(now))
                    .macdSignalWatching(watchingInd.macdSignalAtTs(now))
                    .bbWidthWatching(watchingInd.bbWidthAtTs(now))
                    .bbPctBWatching(watchingInd.bbPctBAtTs(now))
                    .adxConfirm(confirmInd.adxAtTs(now))
                    .adxConfirmEma(confirmInd.adxEmaAtTs(now))
                    .dailyRsi(dailyInd.rsiAtTs(now))
                    .dailyAdx(dailyInd.adxAtTs(now))
                    .dailyAdxEma(dailyInd.adxEmaAtTs(now))
                    .macdDaily(dailyInd.macdLineAtTs(now))
                    .macdSignalDaily(dailyInd.macdSignalAtTs(now))
                    .bbWidthDaily(dailyInd.bbWidthAtTs(now))
                    .bbPctBDaily(dailyInd.bbPctBAtTs(now))
                    .bbExpanding(watchingInd.bbExpandingAtTs(now, 5))
                    .bbAligned(watchingInd.bbAlignedAtTs(now, 5, signal.getDirection() == com.dtech.kitecon.trade.enums.TradeDirection.LONG))
                    .rsiSlope(watchingInd.rsiSlopeAtTs(now, 5))
                    .macdHistSlope(watchingInd.macdHistSlopeAtTs(now, 5))
                    .adxSlope(watchingInd.adxSlopeAtTs(now, 5))
                    .build();
        } catch (Exception e) {
            log.warn("[PatternScanService] computeCurrentIndicators failed for {}: {}", signal.getSymbol(), e.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildOverlays(List<DetectedPattern> patterns, List<Bar> bars, Map<Instant, Integer> tsToIdx) {
        List<Map<String, Object>> hlines = new ArrayList<>();
        List<Map<String, Object>> multiPointLines = new ArrayList<>();

        for (DetectedPattern p : patterns) {
            String color = p.isBullish() ? "#4caf50" : "#ef5350";
            String targetColor = "#ffb300";

            // Key level hline (neckline / breakout level)
            if (p.getKeyLevel() > 0 && p.getKeyLevelTime() != null) {
                hlines.add(buildHLine(p.getKeyLevelTime(), p.getKeyLevel(), color, 2, "solid"));
            }

            // Target hline
            if (p.getOwnTarget() > 0 && p.getKeyLevelTime() != null) {
                hlines.add(buildHLine(p.getKeyLevelTime(), p.getOwnTarget(), targetColor, 1, "dashed"));
            }

            // Pattern shape: multi-point-line through P0 → P1 → P2 (for DTB) or pivot sequence (for others)
            List<Map<String, Object>> shapePoints = buildPatternShape(p, bars, tsToIdx);
            if (!shapePoints.isEmpty()) {
                Map<String, Object> line = new HashMap<>();
                line.put("points", shapePoints);
                Map<String, Object> props = new HashMap<>();
                props.put("color", color);
                props.put("width", 2);
                props.put("style", "solid");
                line.put("props", props);
                multiPointLines.add(line);
            }
        }

        Map<String, Object> overlays = new LinkedHashMap<>();
        overlays.put("hline", hlines);
        overlays.put("multi-point-line", multiPointLines);
        return overlays;
    }

    private List<Map<String, Object>> buildPatternShape(DetectedPattern p, List<Bar> bars, Map<Instant, Integer> tsToIdx) {
        List<Map<String, Object>> points = new ArrayList<>();
        String type = p.getPatternType();

        if (type.equals("DOUBLE_BOTTOM") || type.equals("DOUBLE_TOP")) {
            // P0 (first top/bottom) → P1 (neckline) → P2 (second top/bottom = keyLevelTime)
            if (p.getPivotBTime() != null) {
                Integer idx0 = tsToIdx.get(p.getPivotBTime());
                if (idx0 != null) {
                    Bar b0 = bars.get(idx0);
                    points.add(point(p.getPivotBTime(), type.equals("DOUBLE_BOTTOM") ? b0.getLowPrice().doubleValue() : b0.getHighPrice().doubleValue()));
                }
            }
            if (p.getPivotDTime() != null) {
                Integer idx1 = tsToIdx.get(p.getPivotDTime());
                if (idx1 != null) {
                    Bar b1 = bars.get(idx1);
                    points.add(point(p.getPivotDTime(), type.equals("DOUBLE_BOTTOM") ? b1.getHighPrice().doubleValue() : b1.getLowPrice().doubleValue()));
                }
            }
            if (p.getKeyLevelTime() != null) {
                Integer idx2 = tsToIdx.get(p.getKeyLevelTime());
                if (idx2 != null) {
                    Bar b2 = bars.get(idx2);
                    points.add(point(p.getKeyLevelTime(), type.equals("DOUBLE_BOTTOM") ? b2.getLowPrice().doubleValue() : b2.getHighPrice().doubleValue()));
                }
            }
        } else if (type.startsWith("TRIANGLE") || type.startsWith("HNS") || type.startsWith("FLAG") || type.equals("TRENDLINE_BREAKOUT")) {
            // Use pivotBTime and pivotDTime as anchor points, keyLevelTime as apex/breakout
            if (p.getPivotBTime() != null) {
                Integer idx = tsToIdx.get(p.getPivotBTime());
                if (idx != null) points.add(point(p.getPivotBTime(), bars.get(idx).getClosePrice().doubleValue()));
            }
            if (p.getPivotDTime() != null) {
                Integer idx = tsToIdx.get(p.getPivotDTime());
                if (idx != null) points.add(point(p.getPivotDTime(), bars.get(idx).getClosePrice().doubleValue()));
            }
            if (p.getKeyLevelTime() != null) {
                points.add(point(p.getKeyLevelTime(), p.getKeyLevel()));
            }
        }
        return points;
    }

    private Map<String, Object> buildHLine(Instant time, double price, String color, int width, String style) {
        Map<String, Object> hline = new HashMap<>();
        List<Map<String, Object>> pts = new ArrayList<>();
        pts.add(point(time, price));
        hline.put("points", pts);
        Map<String, Object> props = new HashMap<>();
        props.put("color", color);
        props.put("width", width);
        props.put("style", style);
        hline.put("props", props);
        return hline;
    }

    private Map<String, Object> point(Instant time, double price) {
        Map<String, Object> pt = new HashMap<>();
        pt.put("time", time.getEpochSecond());
        pt.put("price", price);
        return pt;
    }
}
