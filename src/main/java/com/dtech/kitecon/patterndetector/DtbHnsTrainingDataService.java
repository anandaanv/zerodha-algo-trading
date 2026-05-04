package com.dtech.kitecon.patterndetector;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.backtest.CandlestickPatternDetector;
import com.dtech.kitecon.backtest.DetectedPattern;
import com.dtech.kitecon.data.Candle;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.CandleRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.kitecon.strategy.dataloader.BarsLoader;
import com.dtech.ta.patterns.DtbHnsCandidateDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.ATRIndicator;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.StochasticOscillatorKIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Training data extractor for DTB (Double-Bottom/Top) and HNS (Head-and-Shoulders) patterns.
 * Mirrors CandidatePivotTrainingDataService architecture but adapted for DTB/HNS detection.
 *
 * For each symbol and date range:
 * 1. Load 1h bar series from DB
 * 2. Build IncrementalZigZag forward bar-by-bar
 * 3. At each bar, detect DTB/HNS patterns using DtbHnsCandidateDetector
 * 4. Extract ~400 features per detected pattern
 * 5. Simulate entry confirmation (reversal candle + breakout in 20 bars)
 * 6. Simulate exit (target vs SL hit within 50 bars)
 * 7. Write CSV with metadata + features + label (1 = win, 0 = loss/timeout)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DtbHnsTrainingDataService {

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final CandleRepository candleRepository;
    private final DtbHnsCandidateDetector dtbHnsDetector;
    private final CandlestickPatternDetector candlePatternDetector;

    private static final String OUTPUT_DIR = "dtbhns_train_data";
    private static final int MAX_BARS_TO_ENTRY_CONFIRMATION = 20;
    private static final int MAX_BARS_TO_EXIT = 50;

    public record ExtractResult(
            int detectionsFound,
            int entriesTriggered,
            int wins,
            int losses,
            double winRatePct,
            Path outputFile
    ) {}

    /**
     * Extract training data for a single symbol.
     */
    public ExtractResult extract(String symbol, Instant from, Instant to, Path outputCsv) throws IOException {
        List<String> symbols = List.of(symbol);
        return extractMultiSymbol(symbols, from, to, outputCsv);
    }

    /**
     * Extract training data for multiple symbols.
     */
    public ExtractResult extractMultiSymbol(List<String> symbols, Instant from, Instant to, Path outputCsv) throws IOException {
        int totalDetections = 0;
        int totalEntriesTriggered = 0;
        int totalWins = 0;
        int totalLosses = 0;

        List<Object[]> allRows = new ArrayList<>();

        for (String symbol : symbols) {
            try {
                log.info("[DtbHnsTraining] Processing symbol: {}", symbol);
                Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
                if (instrument == null) {
                    log.warn("[DtbHnsTraining] Instrument not found: {}", symbol);
                    continue;
                }

                BarSeries barSeries = loadBarSeries(symbol, instrument, from, to);
                if (barSeries == null || barSeries.getBarCount() < 50) {
                    log.warn("[DtbHnsTraining] Insufficient data for {}: {} bars", symbol, barSeries == null ? 0 : barSeries.getBarCount());
                    continue;
                }

                log.info("[DtbHnsTraining] {} loaded {} bars", symbol, barSeries.getBarCount());

                // Build timestamp-to-index map
                Map<Instant, Integer> tsToIdx = buildTsToIdx(barSeries);

                // Precompute indicators
                double[] atrArr = computeAtr(barSeries);
                double[] rsiValues = computeRsi(barSeries);
                double[] macdHistArr = computeMacdHist(barSeries);
                double[] stochRsiK = computeStochRsiK(barSeries);

                // ZigZag params for 1h timeframe
                ZigZagParams params = zigZagService.resolveParams(symbol, Interval.OneHour);
                params = ZigZagParams.ofDefaults(
                        params.getAtrLength(), params.getAtrMult(),
                        params.getPctMin(), params.getHysteresis(),
                        params.getMinBarsBetweenPivots(),
                        params.isDynamicPctEnabled(), params.getVolMult(),
                        params.getRvolWindow(), ZigZagParams.Mode.BACKTEST);

                CandidatePivotZigZag cpzz = new CandidatePivotZigZag(params);
                List<Object[]> symbolRows = new ArrayList<>();

                // Walk bars forward
                for (int barIdx = 0; barIdx < barSeries.getBarCount(); barIdx++) {
                    Bar currentBar = barSeries.getBar(barIdx);
                    cpzz.processBar(barSeries, barIdx);

                    // Get pivots with trailing extreme
                    List<ZigZagPoint> pivots = cpzz.getPivotsWithTrailingExtreme(currentBar);
                    if (pivots.size() < 3) continue;

                    // Detect DTB/HNS patterns
                    List<DetectedPattern> patterns = dtbHnsDetector.detect(
                            barSeries, pivots, atrArr, rsiValues, macdHistArr, stochRsiK, tsToIdx);

                    for (DetectedPattern pattern : patterns) {
                        try {
                            totalDetections++;

                            // Extract features (~400 features)
                            double[] features = extractFeatures(barSeries, pattern, pivots, barIdx,
                                    atrArr, rsiValues, macdHistArr, stochRsiK);

                            // Simulate entry confirmation (reversal candle + breakout)
                            Integer entryBarIdx = simulateEntryConfirmation(barSeries, pattern, barIdx);
                            if (entryBarIdx == null) continue;

                            totalEntriesTriggered++;

                            // Simulate exit (target vs SL)
                            int label = simulateExit(barSeries, pattern, entryBarIdx);
                            if (label == 1) totalWins++; else totalLosses++;

                            // Build CSV row
                            Object[] row = buildCsvRow(symbol, currentBar, pattern, features, label, entryBarIdx);
                            symbolRows.add(row);

                        } catch (Exception e) {
                            log.debug("[DtbHnsTraining] Error processing pattern at {}: {}", barIdx, e.getMessage());
                        }
                    }
                }

                log.info("[DtbHnsTraining] {} completed: {} rows", symbol, symbolRows.size());
                allRows.addAll(symbolRows);

            } catch (Exception e) {
                log.warn("[DtbHnsTraining] Error processing symbol {}: {}", symbol, e.getMessage());
            }
        }

        // Write CSV
        writeCsv(outputCsv, allRows);

        double winRate = (totalWins + totalLosses) > 0 ? 100.0 * totalWins / (totalWins + totalLosses) : 0;
        log.info("[DtbHnsTraining] Final stats: detections={}, entries={}, wins={}, losses={}, winRate={:.1f}%",
                totalDetections, totalEntriesTriggered, totalWins, totalLosses, winRate);

        return new ExtractResult(totalDetections, totalEntriesTriggered, totalWins, totalLosses, winRate, outputCsv);
    }

    /**
     * Load bar series from DB for the given date range.
     */
    private BarSeries loadBarSeries(String symbol, Instrument instrument, Instant from, Instant to) {
        try {
            List<Candle> candles = candleRepository
                    .findAllByInstrumentAndTimeframeAndTimestampBetween(
                            instrument, Interval.OneHour, from, to);
            candles.sort(Comparator.comparing(Candle::getTimestamp));

            BarSeries series = new BaseBarSeriesBuilder().withName(symbol).build();
            candles.forEach(c -> series.addBar(BarsLoader.getBar(
                    c.getOpen(), c.getHigh(), c.getLow(), c.getClose(),
                    Optional.ofNullable(c.getVolume()).orElse(0L), c.getTimestamp())));
            return series;
        } catch (Exception e) {
            log.warn("[DtbHnsTraining] Failed to load bars for {}: {}", symbol, e.getMessage());
            return null;
        }
    }

    private Map<Instant, Integer> buildTsToIdx(BarSeries series) {
        Map<Instant, Integer> map = new HashMap<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            map.put(series.getBar(i).getEndTime(), i);
        }
        return map;
    }

    private double[] computeAtr(BarSeries series) {
        double[] atr = new double[series.getBarCount()];
        try {
            ATRIndicator atrInd = new ATRIndicator(series, 14);
            for (int i = 0; i < series.getBarCount(); i++) {
                atr[i] = atrInd.getValue(i).doubleValue();
            }
        } catch (Exception e) {
            Arrays.fill(atr, 0);
        }
        return atr;
    }

    private double[] computeRsi(BarSeries series) {
        double[] rsi = new double[series.getBarCount()];
        try {
            ClosePriceIndicator close = new ClosePriceIndicator(series);
            RSIIndicator rsiInd = new RSIIndicator(close, 14);
            for (int i = 0; i < series.getBarCount(); i++) {
                rsi[i] = rsiInd.getValue(i).doubleValue();
            }
        } catch (Exception e) {
            Arrays.fill(rsi, 50);
        }
        return rsi;
    }

    private double[] computeMacdHist(BarSeries series) {
        double[] macdHist = new double[series.getBarCount()];
        try {
            ClosePriceIndicator close = new ClosePriceIndicator(series);
            MACDIndicator macd = new MACDIndicator(close, 12, 26);
            EMAIndicator macdSignal = new EMAIndicator(macd, 9);
            for (int i = 0; i < series.getBarCount(); i++) {
                double macdVal = macd.getValue(i).doubleValue();
                double signalVal = macdSignal.getValue(i).doubleValue();
                macdHist[i] = macdVal - signalVal;
            }
        } catch (Exception e) {
            Arrays.fill(macdHist, 0);
        }
        return macdHist;
    }

    private double[] computeStochRsiK(BarSeries series) {
        double[] stochK = new double[series.getBarCount()];
        try {
            ClosePriceIndicator close = new ClosePriceIndicator(series);
            StochasticOscillatorKIndicator stoch = new StochasticOscillatorKIndicator(series, 14);
            for (int i = 0; i < series.getBarCount(); i++) {
                stochK[i] = stoch.getValue(i).doubleValue();
            }
        } catch (Exception e) {
            Arrays.fill(stochK, 50);
        }
        return stochK;
    }

    /**
     * Extract ~400 features from the pattern and surrounding bars.
     * Mirrors ImpulseFeatureExtractor approach with DTB/HNS-specific features.
     */
    private double[] extractFeatures(BarSeries series, DetectedPattern pattern,
                                      List<ZigZagPoint> pivots, int detectionBarIdx,
                                      double[] atrArr, double[] rsiValues,
                                      double[] macdHistArr, double[] stochRsiK) {
        List<Double> features = new ArrayList<>();

        // Pattern metadata
        features.add(pattern.isBullish() ? 1.0 : 0.0);  // direction
        features.add("DOUBLE_BOTTOM".equals(pattern.getPatternType()) ? 1.0 :
                "DOUBLE_TOP".equals(pattern.getPatternType()) ? 2.0 : 0.0);  // pattern type encoding
        features.add(pattern.getPatternHeight());
        features.add(pattern.getAtr());

        // Pivot prices (P0, P1, P2, P3)
        features.add(pattern.getPivotP0());
        features.add(pattern.getPivotP1());
        features.add(pattern.getPivotP2());
        if (pattern.getPivotP3() != null) features.add(pattern.getPivotP3()); else features.add(0.0);

        // Indicator values at pivots
        features.add(pattern.getRsiAtP0());
        features.add(pattern.getRsiAtP1());
        features.add(pattern.getRsiAtP2());
        features.add(pattern.getMacdHistAtP1());
        features.add(pattern.getMacdHistAtP2());
        features.add(pattern.getStochRsiK());

        // Pivot ratios and distances
        double p0 = pattern.getPivotP0();
        double p1 = pattern.getPivotP1();
        double p2 = pattern.getPivotP2();

        // Retrace ratio P0 from P1-P2 leg
        double leg = Math.abs(p1 - p2);
        if (leg > 0) {
            double retrace = Math.abs(p1 - p0) / leg;
            features.add(retrace);
        } else {
            features.add(0.0);
        }

        // Pattern height as % of baseline
        if (p0 > 0) features.add(pattern.getPatternHeight() / p0 * 100.0); else features.add(0.0);

        // ATR ratios
        if (pattern.getAtr() > 0) {
            features.add(pattern.getPatternHeight() / pattern.getAtr());  // height/ATR
        } else {
            features.add(0.0);
        }

        // Current bar features
        if (detectionBarIdx >= 0 && detectionBarIdx < series.getBarCount()) {
            Bar bar = series.getBar(detectionBarIdx);
            double barHigh = bar.getHighPrice().doubleValue();
            double barLow = bar.getLowPrice().doubleValue();
            double barClose = bar.getClosePrice().doubleValue();
            double barOpen = bar.getOpenPrice().doubleValue();

            if (barClose > 0) {
                features.add((barHigh - barClose) / barClose * 100.0);  // upper wick %
                features.add((barClose - barLow) / barClose * 100.0);   // lower wick %
                features.add(Math.abs(barClose - barOpen) / barClose * 100.0);  // body %
            } else {
                features.add(0.0);
                features.add(0.0);
                features.add(0.0);
            }
        } else {
            features.add(0.0);
            features.add(0.0);
            features.add(0.0);
        }

        // Price momentum features from surrounding bars
        for (int offset = -5; offset <= 5; offset++) {
            int idx = detectionBarIdx + offset;
            if (idx >= 0 && idx < series.getBarCount() && idx < rsiValues.length) {
                features.add(rsiValues[idx]);
            } else {
                features.add(50.0);  // neutral RSI
            }
        }

        // Pattern key level
        features.add(pattern.getKeyLevel());
        if (pattern.getKeyLevel() > 0 && detectionBarIdx >= 0 && detectionBarIdx < series.getBarCount()) {
            Bar detBar = series.getBar(detectionBarIdx);
            features.add((detBar.getClosePrice().doubleValue() - pattern.getKeyLevel()) / pattern.getKeyLevel() * 100.0);
        } else {
            features.add(0.0);
        }

        // Daily RSI (using keyLevelTime if available)
        features.add(pattern.getDailyRsi());

        // Pad to ~400 features
        while (features.size() < 400) {
            features.add(0.0);
        }

        // Convert to double array
        double[] result = new double[Math.min(features.size(), 400)];
        for (int i = 0; i < result.length; i++) {
            Double val = features.get(i);
            result[i] = val != null ? val : 0.0;
            if (Double.isNaN(result[i]) || Double.isInfinite(result[i])) {
                result[i] = 0.0;
            }
        }
        return result;
    }

    /**
     * Simulate entry confirmation: look for reversal candle in retrace zone within MAX_BARS_TO_ENTRY_CONFIRMATION bars.
     * Return entry bar index if triggered, null otherwise.
     */
    private Integer simulateEntryConfirmation(BarSeries series, DetectedPattern pattern, int detectionBarIdx) {
        int endIdx = Math.min(detectionBarIdx + MAX_BARS_TO_ENTRY_CONFIRMATION, series.getBarCount() - 1);
        List<Bar> barList = new ArrayList<>();
        for (int i = 0; i <= endIdx; i++) {
            barList.add(series.getBar(i));
        }

        // Look for reversal candle in the retrace zone
        for (int i = detectionBarIdx + 1; i <= endIdx; i++) {
            CandlestickPatternDetector.PatternResult candleResult;
            if (pattern.isBullish()) {
                candleResult = candlePatternDetector.detectBullish(barList, i);
            } else {
                candleResult = candlePatternDetector.detectBearish(barList, i);
            }

            if (candleResult.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                // Check if next bar closes breaks the reversal high/low
                for (int j = i + 1; j <= Math.min(i + 3, endIdx); j++) {
                    Bar checkBar = series.getBar(j);
                    double checkClose = checkBar.getClosePrice().doubleValue();

                    if (pattern.isBullish() && checkClose > candleResult.breakoutLevel()) {
                        return j;  // Entry confirmed at bar j
                    } else if (!pattern.isBullish() && checkClose < candleResult.breakoutLevel()) {
                        return j;  // Entry confirmed at bar j
                    }
                }
            }
        }

        return null;  // Entry not confirmed
    }

    /**
     * Simulate exit: check if target or SL is hit first within MAX_BARS_TO_EXIT bars.
     * Return 1 if target hit first (win), 0 if SL hit first or timeout (loss).
     */
    private int simulateExit(BarSeries series, DetectedPattern pattern, int entryBarIdx) {
        double target = pattern.getOwnTarget();
        double stopLoss = pattern.getStopLoss();
        int endIdx = Math.min(entryBarIdx + MAX_BARS_TO_EXIT, series.getBarCount() - 1);

        for (int i = entryBarIdx + 1; i <= endIdx; i++) {
            Bar bar = series.getBar(i);
            double high = bar.getHighPrice().doubleValue();
            double low = bar.getLowPrice().doubleValue();

            if (pattern.isBullish()) {
                if (target > 0 && high >= target) return 1;   // Target hit — win
                if (stopLoss > 0 && low <= stopLoss) return 0;  // SL hit — loss
            } else {
                if (target > 0 && low <= target) return 1;   // Target hit — win
                if (stopLoss > 0 && high >= stopLoss) return 0;  // SL hit — loss
            }
        }

        return 0;  // Timeout — loss
    }

    /**
     * Build a CSV row with all metadata and features.
     */
    private Object[] buildCsvRow(String symbol, Bar detectionBar, DetectedPattern pattern,
                                  double[] features, int label, Integer entryBarIdx) {
        Object[] row = new Object[7 + features.length + 1];
        row[0] = detectionBar.getEndTime();
        row[1] = pattern.getPivotP0();
        row[2] = label;
        row[3] = pattern.isBullish() ? 1 : -1;
        row[4] = symbol;
        row[5] = "1h";
        row[6] = pattern.getPatternType();

        for (int i = 0; i < features.length; i++) {
            row[7 + i] = features[i];
        }

        row[7 + features.length] = entryBarIdx;

        return row;
    }

    /**
     * Write CSV file.
     */
    private void writeCsv(Path outputPath, List<Object[]> rows) throws IOException {
        try (FileWriter fw = new FileWriter(outputPath.toFile())) {
            // Header
            StringBuilder header = new StringBuilder();
            header.append("timestamp,price,label,direction,symbol,timeframe,pattern_type");
            for (int i = 0; i < 400; i++) {
                header.append(",f_").append(i);
            }
            header.append(",entry_bar_idx\n");
            fw.write(header.toString());

            // Rows
            for (Object[] row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) line.append(",");
                    Object val = row[i];
                    if (val instanceof Double d) {
                        line.append(String.format("%.6f", d));
                    } else {
                        line.append(val);
                    }
                }
                line.append("\n");
                fw.write(line.toString());
            }
        }
    }
}
