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
import com.dtech.ta.patterns.DtbHnsFeatureExtractor;
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
    private final DtbHnsFeatureExtractor dtbHnsFeatureExtractor;

    private static final String OUTPUT_DIR = "dtbhns_train_data";
    private static final int MAX_BARS_TO_ENTRY_CONFIRMATION = 20;
    private static final int MAX_BARS_TO_EXIT = 10;

    public record ExtractResult(
            int detectionsFound,
            int entriesTriggered,
            int wins,
            int losses,
            int timedOut,
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
        int totalTimedOut = 0;

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
                            double[] features = dtbHnsFeatureExtractor.extract(barSeries, pattern, pivots, barIdx,
                                    atrArr, rsiValues, macdHistArr, stochRsiK);

                            // Simulate entry confirmation (reversal candle + breakout)
                            Integer entryBarIdx = simulateEntryConfirmation(barSeries, pattern, barIdx);
                            if (entryBarIdx == null) continue;

                            totalEntriesTriggered++;

                            // Simulate exit (target vs SL)
                            Integer label = simulateExit(barSeries, pattern, entryBarIdx);
                            if (label == null) {
                                // Timeout: exclude from training
                                totalTimedOut++;
                                continue;
                            } else if (label == 1) {
                                totalWins++;
                            } else {
                                totalLosses++;
                            }

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
        log.info("[DtbHnsTraining] Final stats: detections={}, entries={}, wins={}, losses={}, timedOut={}, winRate={:.1f}%",
                totalDetections, totalEntriesTriggered, totalWins, totalLosses, totalTimedOut, winRate);

        return new ExtractResult(totalDetections, totalEntriesTriggered, totalWins, totalLosses, totalTimedOut, winRate, outputCsv);
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
     * Return 1 if target hit first (win), 0 if SL hit first (loss), null if timeout (exclude).
     */
    private Integer simulateExit(BarSeries series, DetectedPattern pattern, int entryBarIdx) {
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

        return null;  // Timeout — exclude from training
    }

    /**
     * Build a CSV row with all metadata and features.
     */
    private Object[] buildCsvRow(String symbol, Bar detectionBar, DetectedPattern pattern,
                                  double[] features, Integer label, Integer entryBarIdx) {
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
