package com.dtech.kitecon.backtest;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.MACDIndicator;
import org.ta4j.core.indicators.RSIIndicator;
import org.ta4j.core.indicators.averages.EMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatternComboBacktestService {

    private static final int    MACD_SHORT         = 12;
    private static final int    MACD_LONG          = 26;
    private static final int    MACD_SIGNAL_PERIOD = 9;
    private static final int    RSI_PERIOD         = 14;
    private static final int    ATR_PERIOD         = 14;
    private static final double TRADE_OVERHEAD_PCT = 0.1;
    private static final double ATR_FLAT_FACTOR    = 0.5;
    private static final double ATR_RANGE_FACTOR   = 2.0;
    private static final int    MIN_PATTERN_BARS   = 5;
    private static final int    CONFIRM_WINDOW_BARS_15M = 80;
    private static final double KEY_LEVEL_PROXIMITY_ATR = 1.5;
    private static final double MIN_COMBO_RR = 1.5;
    private static final int    MAX_HOLD_BARS_15M = 400;

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final CandlestickPatternDetector candlestickPatternDetector = new CandlestickPatternDetector();

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry points
    // ─────────────────────────────────────────────────────────────────────────

    public void runAndWriteCsv(String symbol, String csvPath) throws IOException {
        List<ComboRow> combos = runForSymbol(symbol);
        writeCsv(combos, csvPath);
    }

    public void runMultipleAndWriteCsv(List<String> symbols, String csvPath) throws IOException {
        List<ComboRow> allCombos = new ArrayList<>();
        int failed = 0;
        for (String symbol : symbols) {
            try {
                allCombos.addAll(runForSymbol(symbol));
            } catch (Exception e) {
                log.warn("[Combo] Skipping {} due to error: {}", symbol, e.getMessage());
                failed++;
            }
        }
        log.info("[Combo] Total combo trades across {} symbols: {} ({} failed)", symbols.size(), allCombos.size(), failed);
        writeCsv(allCombos, csvPath);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core algorithm
    // ─────────────────────────────────────────────────────────────────────────

    private List<ComboRow> runForSymbol(String symbol) {
        log.info("[Combo] Starting multi-TF combo backtest for {}", symbol);

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            log.error("[Combo] Instrument not found: {}", symbol);
            return new ArrayList<>();
        }

        // Load 1h, 15m, and daily series
        BarSeries series1h = zigZagService.getBarSeries(symbol, instrument, Interval.OneHour);
        BarSeries series15m = zigZagService.getBarSeries(symbol, instrument, Interval.FifteenMinute);
        BarSeries seriesDaily = zigZagService.getBarSeries(symbol, instrument, Interval.Day);

        if (series1h == null || series1h.isEmpty()) {
            log.warn("[Combo] No 1h data for {}", symbol);
            return new ArrayList<>();
        }

        List<Bar> bars1h = toList(series1h);
        Map<Instant, Integer> tsToIdx1h = buildTsToIdx(bars1h);

        // Compute 1h indicators
        double[] atrArr1h = computeAtr(bars1h, ATR_PERIOD);
        double[] rsiValues1h = new double[series1h.getBarCount()];
        RSIIndicator rsi1h = new RSIIndicator(new ClosePriceIndicator(series1h), RSI_PERIOD);
        for (int i = 0; i < series1h.getBarCount(); i++) {
            rsiValues1h[i] = safeDouble(rsi1h.getValue(i));
        }
        double[] stochRsiK1h = computeStochRsiK(rsiValues1h);

        // Compute 1h MACD histogram
        ClosePriceIndicator close1h = new ClosePriceIndicator(series1h);
        MACDIndicator macd1h = new MACDIndicator(close1h, MACD_SHORT, MACD_LONG);
        EMAIndicator macdSignal1h = new EMAIndicator(macd1h, MACD_SIGNAL_PERIOD);
        double[] macdHistArr1h = new double[series1h.getBarCount()];
        for (int i = 0; i < series1h.getBarCount(); i++) {
            double macdVal = safeDouble(macd1h.getValue(i));
            double signalVal = safeDouble(macdSignal1h.getValue(i));
            macdHistArr1h[i] = macdVal - signalVal;
        }

        // Get 1h ZigZag pivots
        ZigZagParams params1h = zigZagService.resolveParams(symbol, Interval.OneHour);
        List<ZigZagPoint> pivots1h = zigZagService.detect(series1h, params1h);
        log.info("[Combo] 1h: {} pivots", pivots1h.size());

        // Detect watching patterns on 1h
        List<DetectedPattern> watchingPatterns = new ArrayList<>();
        watchingPatterns.addAll(scanDtbWatching(pivots1h, bars1h, tsToIdx1h, atrArr1h, rsiValues1h, macdHistArr1h, stochRsiK1h));
        watchingPatterns.addAll(scanTriangleWatching(pivots1h, bars1h, tsToIdx1h, atrArr1h, rsiValues1h, macdHistArr1h, stochRsiK1h));
        log.info("[Combo] Found {} watching patterns (1h)", watchingPatterns.size());

        if (series15m == null || series15m.isEmpty()) {
            log.warn("[Combo] No 15m data for {}", symbol);
            return new ArrayList<>();
        }

        List<Bar> bars15m = toList(series15m);
        Map<Instant, Integer> tsToIdx15m = buildTsToIdx(bars15m);

        // Compute 15m indicators
        double[] atrArr15m = computeAtr(bars15m, ATR_PERIOD);
        double[] rsiValues15m = new double[series15m.getBarCount()];
        RSIIndicator rsi15m = new RSIIndicator(new ClosePriceIndicator(series15m), RSI_PERIOD);
        for (int i = 0; i < series15m.getBarCount(); i++) {
            rsiValues15m[i] = safeDouble(rsi15m.getValue(i));
        }
        double[] stochRsiK15m = computeStochRsiK(rsiValues15m);

        // Compute 15m MACD histogram
        ClosePriceIndicator close15m = new ClosePriceIndicator(series15m);
        MACDIndicator macd15m = new MACDIndicator(close15m, MACD_SHORT, MACD_LONG);
        EMAIndicator macdSignal15m = new EMAIndicator(macd15m, MACD_SIGNAL_PERIOD);
        double[] macdHistArr15m = new double[series15m.getBarCount()];
        for (int i = 0; i < series15m.getBarCount(); i++) {
            double macdVal = safeDouble(macd15m.getValue(i));
            double signalVal = safeDouble(macdSignal15m.getValue(i));
            macdHistArr15m[i] = macdVal - signalVal;
        }

        // Compute daily indicators for 15m entry lookups
        DailyIndicators dailyInd = computeDailyIndicators(seriesDaily);

        // Get 15m ZigZag pivots
        ZigZagParams params15m = zigZagService.resolveParams(symbol, Interval.FifteenMinute);
        List<ZigZagPoint> pivots15m = zigZagService.detect(series15m, params15m);
        log.info("[Combo] 15m: {} pivots", pivots15m.size());

        // Detect confirmation patterns on 15m
        List<DetectedPattern> confirmPatterns = new ArrayList<>();
        confirmPatterns.addAll(scanDtbConfirmation(pivots15m, bars15m, tsToIdx15m, atrArr15m, rsiValues15m, macdHistArr15m, stochRsiK15m, dailyInd));
        confirmPatterns.addAll(scanTriangleConfirmation(pivots15m, bars15m, tsToIdx15m, atrArr15m, rsiValues15m, macdHistArr15m, stochRsiK15m, dailyInd));
        confirmPatterns.addAll(scanCandleClusterConfirmation(bars15m, atrArr15m, rsiValues15m, macdHistArr15m, stochRsiK15m, dailyInd));
        log.info("[Combo] Found {} confirmation patterns (15m)", confirmPatterns.size());

        // Cross-match watching × confirmation
        List<ComboRow> results = new ArrayList<>();
        Set<String> seenCombos = new HashSet<>();

        for (DetectedPattern wp : watchingPatterns) {
            for (DetectedPattern cp : confirmPatterns) {
                // Same direction check
                if (cp.bullish != wp.bullish) continue;

                // Time window check
                if (cp.getKeyLevelTime().isBefore(wp.getKeyLevelTime())) continue;
                if (cp.getKeyLevelTime().isAfter(wp.getKeyLevelTime().plusSeconds(20 * 3600))) continue;

                // Price proximity check
                if (Math.abs(cp.getEntryPrice() - wp.getKeyLevel()) > KEY_LEVEL_PROXIMITY_ATR * wp.getAtr()) continue;

                // Compute combo parameters
                double comboEntry = cp.getEntryPrice();
                double comboSL = cp.getStopLoss();
                double comboTarget = wp.getOwnTarget();

                // Skip if SL is on the wrong side of entry (use price direction as truth)
                boolean bullish = comboTarget > comboEntry;
                if (bullish  && comboSL >= comboEntry) continue;
                if (!bullish && comboSL <= comboEntry) continue;

                double risk = Math.abs(comboEntry - comboSL);
                double reward = Math.abs(comboTarget - comboEntry);
                if (risk <= 0) continue;
                double rr = reward / risk;
                if (rr < MIN_COMBO_RR) continue;

                // Duplicate prevention
                String comboKey = wp.getKeyLevelTime() + "|" + cp.getKeyLevelTime();
                if (seenCombos.contains(comboKey)) continue;
                seenCombos.add(comboKey);

                // Simulate exit
                Integer entryBarIdx15m = tsToIdx15m.get(cp.getKeyLevelTime());
                if (entryBarIdx15m == null) continue;

                String result = "OPEN";
                int barsToResult = 0;
                double pnlPct = 0.0;
                double exitPrice = 0.0;
                String exitReason = "OPEN";

                // Exit simulation — use price-direction bullish (set above, consistent with SL guard)
                for (int k = entryBarIdx15m + 1; k < Math.min(bars15m.size(), entryBarIdx15m + MAX_HOLD_BARS_15M); k++) {
                    Bar b = bars15m.get(k);

                    // SL check
                    if (bullish && b.getLowPrice().doubleValue() <= comboSL) {
                        result = "STOP_HIT";
                        exitReason = "STOP_HIT";
                        exitPrice = (k + 1 < bars15m.size()) ? bars15m.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                        pnlPct = (exitPrice - comboEntry) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                        barsToResult = k - entryBarIdx15m;
                        break;
                    }
                    if (!bullish && b.getHighPrice().doubleValue() >= comboSL) {
                        result = "STOP_HIT";
                        exitReason = "STOP_HIT";
                        exitPrice = (k + 1 < bars15m.size()) ? bars15m.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                        pnlPct = (comboEntry - exitPrice) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                        barsToResult = k - entryBarIdx15m;
                        break;
                    }

                    // Target check
                    if (bullish && b.getHighPrice().doubleValue() >= comboTarget) {
                        result = "WIN";
                        exitReason = "TARGET_HIT";
                        exitPrice = comboTarget;
                        pnlPct = (comboTarget - comboEntry) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                        barsToResult = k - entryBarIdx15m;
                        break;
                    }
                    if (!bullish && b.getLowPrice().doubleValue() <= comboTarget) {
                        result = "WIN";
                        exitReason = "TARGET_HIT";
                        exitPrice = comboTarget;
                        pnlPct = (comboEntry - comboTarget) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                        barsToResult = k - entryBarIdx15m;
                        break;
                    }

                    // Reversal candle check (after 6 bars)
                    if (k >= entryBarIdx15m + 6) {
                        CandlestickPatternDetector.PatternResult rev = bullish
                            ? candlestickPatternDetector.detectBearish(bars15m, k)
                            : candlestickPatternDetector.detectBullish(bars15m, k);
                        if (rev.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                            exitReason = bullish ? "BEARISH_CANDLE" : "BULLISH_CANDLE";
                            exitPrice = (k + 1 < bars15m.size()) ? bars15m.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                            pnlPct = bullish
                                ? (exitPrice - comboEntry) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT
                                : (comboEntry - exitPrice) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                            result = pnlPct > 0 ? "WIN" : "LOSS";
                            barsToResult = k - entryBarIdx15m;
                            break;
                        }
                    }
                }

                results.add(ComboRow.builder()
                        .symbol(symbol)
                        .watchingPattern(wp.getPatternType())
                        .watchingTf("1h")
                        .confirmPattern(cp.getPatternType())
                        .confirmTf("15m")
                        .confirmType(cp.getConfirmationType())
                        .entryTime(cp.getKeyLevelTime())
                        .entryPrice(comboEntry)
                        .stopLoss(comboSL)
                        .watchingTarget(comboTarget)
                        .confirmOwnTarget(cp.getOwnTarget())
                        .rrWatching(rr)
                        .keyLevel(wp.getKeyLevel())
                        .result(result)
                        .barsToResult(barsToResult)
                        .pnlPct(pnlPct)
                        .exitPrice(exitPrice)
                        .exitReason(exitReason)
                        .rsiAtP1(cp.getRsiAtP1())
                        .rsiAtP2(cp.getRsiAtP2())
                        .macdHistAtP1(cp.getMacdHistAtP1())
                        .macdHistAtP2(cp.getMacdHistAtP2())
                        .stochRsiK15m(cp.getStochRsiK())
                        .dailyRsi(cp.getDailyRsi())
                        .watchingPatternHeight(wp.getPatternHeight())
                        .confirmPatternHeight(cp.getPatternHeight())
                        .build());
            }
        }

        log.info("[Combo] Generated {} combo trades", results.size());
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Scanning methods
    // ─────────────────────────────────────────────────────────────────────────

    private List<DetectedPattern> scanDtbWatching(List<ZigZagPoint> pivots, List<Bar> bars,
            Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK) {
        List<DetectedPattern> results = new ArrayList<>();

        for (int i = 2; i < pivots.size(); i++) {
            ZigZagPoint p0 = pivots.get(i - 2);
            ZigZagPoint p1 = pivots.get(i - 1);
            ZigZagPoint p2 = pivots.get(i);
            ZigZagPoint priorPivot = i >= 4 ? pivots.get(i - 4) : null;

            // Double Bottom: L, H, L
            if (p0.isLow() && p1.isHigh() && p2.isLow()) {
                detectDtbWatching(p0, p1, p2, priorPivot, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, results, true);
            }

            // Double Top: H, L, H
            if (p0.isHigh() && p1.isLow() && p2.isHigh()) {
                detectDtbWatching(p0, p1, p2, priorPivot, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, results, false);
            }
        }
        return results;
    }

    private void detectDtbWatching(ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2,
            ZigZagPoint priorPivot, List<Bar> bars, Map<Instant, Integer> tsToIdx,
            double[] atrArr, double[] rsiValues, double[] macdHistArr, double[] stochRsiK,
            List<DetectedPattern> results, boolean bullish) {

        double price0 = p0.getValue();
        double price1 = p1.getValue();
        double price2 = p2.getValue();

        double swing = Math.abs(price1 - price0);
        if (swing <= 0) return;
        double retrace = Math.abs(price1 - price2) / swing;
        if (retrace < 0.62 || retrace > 1.005) return;

        if (Math.abs(price0 - price2) / Math.abs(price0) > 0.04) return;

        Integer idx2 = tsToIdx.get(p2.getTimestamp());
        if (idx2 == null) return;

        if (priorPivot == null) return;
        Integer priorIdx = tsToIdx.get(priorPivot.getTimestamp());
        if (priorIdx == null) return;

        double rsiAtPrior = rsiValues[priorIdx];
        double rsiAtP2 = rsiValues[idx2];
        boolean hasDivergence = rsiAtP2 > rsiAtPrior;
        if (!hasDivergence) return;

        double patternHeight = Math.abs(price1 - Math.min(price0, price2));
        double target = bullish ? price1 + patternHeight : price1 - patternHeight;

        double macdHistAtPrior = priorIdx < macdHistArr.length ? macdHistArr[priorIdx] : 0;
        double macdHistAtP2 = idx2 < macdHistArr.length ? macdHistArr[idx2] : 0;

        results.add(DetectedPattern.builder()
                .patternType(bullish ? "DOUBLE_BOTTOM" : "DOUBLE_TOP")
                .confirmationType(null)
                .bullish(bullish)
                .keyLevel(price2)
                .keyLevelTime(p2.getTimestamp())
                .entryPrice(0)
                .stopLoss(0)
                .ownTarget(target)
                .patternHeight(patternHeight)
                .atr(idx2 < atrArr.length ? atrArr[idx2] : 0)
                .rsiAtP1(rsiAtPrior)
                .rsiAtP2(rsiAtP2)
                .macdHistAtP1(macdHistAtPrior)
                .macdHistAtP2(macdHistAtP2)
                .stochRsiK(idx2 < stochRsiK.length ? stochRsiK[idx2] : 0)
                .dailyRsi(0)
                .macdHistogram(macdHistAtP2)
                .reversalPattern(null)
                .build());
    }

    private List<DetectedPattern> scanTriangleWatching(List<ZigZagPoint> pivots, List<Bar> bars,
            Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK) {
        List<DetectedPattern> results = new ArrayList<>();

        for (int i = 4; i < pivots.size(); i++) {
            ZigZagPoint p0 = pivots.get(i - 4);
            ZigZagPoint p1 = pivots.get(i - 3);
            ZigZagPoint p2 = pivots.get(i - 2);
            ZigZagPoint p3 = pivots.get(i - 1);
            ZigZagPoint p4 = pivots.get(i);

            // Both orientations
            checkTriangleWatching(p0, p1, p2, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, results);
        }
        return results;
    }

    private void checkTriangleWatching(ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2, ZigZagPoint p3, ZigZagPoint p4,
            List<Bar> bars, Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, List<DetectedPattern> results) {

        // High-first: H, L, H, L, H
        if (p0.isHigh() && p1.isLow() && p2.isHigh() && p3.isLow() && p4.isHigh()) {
            processTriangleWatching(p0, p2, p1, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, results, true);
        }

        // Low-first: L, H, L, H, L
        if (p0.isLow() && p1.isHigh() && p2.isLow() && p3.isHigh() && p4.isLow()) {
            processTriangleWatching(p0, p2, p1, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, results, false);
        }
    }

    private void processTriangleWatching(ZigZagPoint pHigh1, ZigZagPoint pHigh2, ZigZagPoint pLow1, ZigZagPoint pLow2, ZigZagPoint pLast,
            List<Bar> bars, Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, List<DetectedPattern> results, boolean isHighFirst) {

        Integer h1Idx = idx(tsToIdx, pHigh1);
        Integer h2Idx = idx(tsToIdx, pHigh2);
        Integer l1Idx = idx(tsToIdx, pLow1);
        Integer l2Idx = idx(tsToIdx, pLow2);
        Integer lastIdx = idx(tsToIdx, pLast);

        if (h1Idx < 0 || h2Idx < 0 || l1Idx < 0 || l2Idx < 0 || lastIdx < 0) return;

        double h1 = pHigh1.getValue();
        double h2 = pHigh2.getValue();
        double l1 = pLow1.getValue();
        double l2 = pLow2.getValue();
        double lastPrice = pLast.getValue();

        int duration = lastIdx - Math.min(h1Idx, l1Idx);
        if (duration < MIN_PATTERN_BARS) return;

        double patternHeight = Math.max(h1, h2) - Math.min(l1, l2);
        double atrAtLast = lastIdx < atrArr.length ? atrArr[lastIdx] : 0;
        if (patternHeight < ATR_RANGE_FACTOR * atrAtLast) return;

        double upperSlope = (h2 - h1) / Math.max(1, h2Idx - h1Idx);
        double lowerSlope = (l2 - l1) / Math.max(1, l2Idx - l1Idx);

        // Classify triangle using ATR-based checks (consistent with TriangleBacktestService)
        boolean upperFalling = h2 < h1 - ATR_FLAT_FACTOR * atrAtLast;
        boolean upperRising  = h2 > h1 + ATR_FLAT_FACTOR * atrAtLast;
        boolean lowerRising  = l2 > l1 + ATR_FLAT_FACTOR * atrAtLast;
        boolean lowerFalling = l2 < l1 - ATR_FLAT_FACTOR * atrAtLast;
        boolean upperFlat    = !upperFalling && !upperRising;
        boolean lowerFlat    = !lowerRising  && !lowerFalling;

        String triangleType;
        boolean bullish;

        if      (upperFalling && lowerRising)                   { triangleType = "TRIANGLE_SYMMETRIC";   bullish = true;  }
        else if (lowerRising  && (upperFlat || upperFalling))   { triangleType = "TRIANGLE_ASCENDING";   bullish = true;  }
        else if (upperFalling && (lowerFlat || lowerFalling))   { triangleType = "TRIANGLE_DESCENDING";  bullish = false; }
        else if (upperRising  && lowerFalling)                  { return; /* expanding — skip */          }
        else                                                    { return; /* unclassified */               }

        // Verify last pivot is consistent with its own trendline (within 1 ATR)
        if (isHighFirst) {
            // last pivot is a HIGH: must be near projected upper trendline
            double projH3 = h1 + upperSlope * (lastIdx - h1Idx);
            if (Math.abs(lastPrice - projH3) > atrAtLast) return;
        } else {
            // last pivot is a LOW: must be near projected lower trendline
            double projL3 = l1 + lowerSlope * (lastIdx - l1Idx);
            if (Math.abs(lastPrice - projL3) > atrAtLast) return;
        }

        // SYMMETRIC: use prior-impulse direction (same as TriangleBacktestService)
        if ("TRIANGLE_SYMMETRIC".equals(triangleType)) {
            // For simplicity in watching patterns, default to bullish for low-first (low at end = support held)
            bullish = !isHighFirst;
        }

        // Compute projected trendlines at last pivot bar for target calculation
        double projectedUpper = h1 + upperSlope * (lastIdx - h1Idx);
        double projectedLower = l1 + lowerSlope * (lastIdx - l1Idx);

        // Compute target
        double target;
        if ("TRIANGLE_ASCENDING".equals(triangleType)) {
            target = Math.max(h1, h2) + patternHeight;
        } else if ("TRIANGLE_DESCENDING".equals(triangleType)) {
            target = Math.min(l1, l2) - patternHeight;
        } else {
            target = bullish
                ? projectedUpper + patternHeight
                : projectedLower - patternHeight;
        }

        double rsiAtP1 = l1Idx < rsiValues.length ? rsiValues[l1Idx] : 0;
        double rsiAtP2 = l2Idx < rsiValues.length ? rsiValues[l2Idx] : 0;
        double macdHistAtP1 = l1Idx < macdHistArr.length ? macdHistArr[l1Idx] : 0;
        double macdHistAtP2 = l2Idx < macdHistArr.length ? macdHistArr[l2Idx] : 0;

        results.add(DetectedPattern.builder()
                .patternType(triangleType)
                .confirmationType(null)
                .bullish(bullish)
                .keyLevel(lastPrice)
                .keyLevelTime(pLast.getTimestamp())
                .entryPrice(0)
                .stopLoss(0)
                .ownTarget(target)
                .patternHeight(patternHeight)
                .atr(atrAtLast)
                .rsiAtP1(rsiAtP1)
                .rsiAtP2(rsiAtP2)
                .macdHistAtP1(macdHistAtP1)
                .macdHistAtP2(macdHistAtP2)
                .stochRsiK(lastIdx < stochRsiK.length ? stochRsiK[lastIdx] : 0)
                .dailyRsi(0)
                .macdHistogram(macdHistAtP2)
                .reversalPattern(null)
                .build());
    }

    private List<DetectedPattern> scanDtbConfirmation(List<ZigZagPoint> pivots, List<Bar> bars,
            Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, DailyIndicators dailyInd) {
        List<DetectedPattern> results = new ArrayList<>();

        for (int i = 2; i < pivots.size(); i++) {
            ZigZagPoint p0 = pivots.get(i - 2);
            ZigZagPoint p1 = pivots.get(i - 1);
            ZigZagPoint p2 = pivots.get(i);
            ZigZagPoint priorPivot = i >= 4 ? pivots.get(i - 4) : null;

            if (p0.isLow() && p1.isHigh() && p2.isLow()) {
                detectDtbConfirmation(p0, p1, p2, priorPivot, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, dailyInd, results, true);
            }

            if (p0.isHigh() && p1.isLow() && p2.isHigh()) {
                detectDtbConfirmation(p0, p1, p2, priorPivot, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, dailyInd, results, false);
            }
        }
        return results;
    }

    private void detectDtbConfirmation(ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2, ZigZagPoint priorPivot,
            List<Bar> bars, Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, DailyIndicators dailyInd,
            List<DetectedPattern> results, boolean bullish) {

        double price0 = p0.getValue();
        double price1 = p1.getValue();
        double price2 = p2.getValue();

        double swing = Math.abs(price1 - price0);
        if (swing <= 0) return;
        double retrace = Math.abs(price1 - price2) / swing;
        if (retrace < 0.62 || retrace > 1.005) return;

        if (Math.abs(price0 - price2) / Math.abs(price0) > 0.04) return;

        Integer idx2 = tsToIdx.get(p2.getTimestamp());
        if (idx2 == null) return;

        if (priorPivot == null) return;
        Integer priorIdx = tsToIdx.get(priorPivot.getTimestamp());
        if (priorIdx == null) return;

        double rsiAtPrior = rsiValues[priorIdx];
        double rsiAtP2 = rsiValues[idx2];
        boolean hasDivergence = rsiAtP2 > rsiAtPrior;
        if (!hasDivergence) return;

        double patternHeight = Math.abs(price1 - Math.min(price0, price2));

        // Look for C1 entry
        int scanStart = Math.max(0, idx2 - 3);
        int scanEnd = Math.min(bars.size() - 1, idx2 + 3);
        for (int i = scanStart; i <= scanEnd; i++) {
            CandlestickPatternDetector.PatternResult rev = bullish
                ? candlestickPatternDetector.detectBullish(bars, i)
                : candlestickPatternDetector.detectBearish(bars, i);
            if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

            double breakoutLevel = rev.breakoutLevel();
            for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                Bar bar = bars.get(j);
                boolean breakout = bullish
                    ? bar.getHighPrice().doubleValue() > breakoutLevel
                    : bar.getLowPrice().doubleValue() < breakoutLevel;

                if (breakout) {
                    double entryPrice = bar.getOpenPrice().doubleValue();
                    double stopLoss = bullish
                        ? Math.min(price0, price2)
                        : Math.max(price0, price2);
                    double target = bullish ? price1 + patternHeight : price1 - patternHeight;

                    double macdHistAtPrior = priorIdx < macdHistArr.length ? macdHistArr[priorIdx] : 0;
                    double macdHistAtP2 = idx2 < macdHistArr.length ? macdHistArr[idx2] : 0;
                    double jAtr = j < atrArr.length ? atrArr[j] : 0;

                    results.add(DetectedPattern.builder()
                            .patternType(bullish ? "DOUBLE_BOTTOM" : "DOUBLE_TOP")
                            .confirmationType("C1")
                            .bullish(bullish)
                            .keyLevel(entryPrice)
                            .keyLevelTime(bar.getEndTime())
                            .entryPrice(entryPrice)
                            .stopLoss(stopLoss)
                            .ownTarget(target)
                            .patternHeight(patternHeight)
                            .atr(jAtr)
                            .rsiAtP1(rsiAtPrior)
                            .rsiAtP2(rsiAtP2)
                            .macdHistAtP1(macdHistAtPrior)
                            .macdHistAtP2(macdHistAtP2)
                            .stochRsiK(j < stochRsiK.length ? stochRsiK[j] : 0)
                            .dailyRsi(dailyInd.rsiAtTs(bar.getEndTime()))
                            .macdHistogram(j < macdHistArr.length ? macdHistArr[j] : 0)
                            .reversalPattern(rev.pattern().name())
                            .build());
                    return;
                }
            }
        }
    }

    private List<DetectedPattern> scanTriangleConfirmation(List<ZigZagPoint> pivots, List<Bar> bars,
            Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, DailyIndicators dailyInd) {
        List<DetectedPattern> results = new ArrayList<>();

        for (int i = 4; i < pivots.size(); i++) {
            ZigZagPoint p0 = pivots.get(i - 4);
            ZigZagPoint p1 = pivots.get(i - 3);
            ZigZagPoint p2 = pivots.get(i - 2);
            ZigZagPoint p3 = pivots.get(i - 1);
            ZigZagPoint p4 = pivots.get(i);

            checkTriangleConfirmation(p0, p1, p2, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, dailyInd, results);
        }
        return results;
    }

    private void checkTriangleConfirmation(ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2, ZigZagPoint p3, ZigZagPoint p4,
            List<Bar> bars, Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, DailyIndicators dailyInd, List<DetectedPattern> results) {

        if (p0.isHigh() && p1.isLow() && p2.isHigh() && p3.isLow() && p4.isHigh()) {
            processTriangleConfirmation(p0, p2, p1, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, dailyInd, results, true);
        }

        if (p0.isLow() && p1.isHigh() && p2.isLow() && p3.isHigh() && p4.isLow()) {
            processTriangleConfirmation(p0, p2, p1, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, dailyInd, results, false);
        }
    }

    private void processTriangleConfirmation(ZigZagPoint pHigh1, ZigZagPoint pHigh2, ZigZagPoint pLow1, ZigZagPoint pLow2, ZigZagPoint pLast,
            List<Bar> bars, Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, DailyIndicators dailyInd, List<DetectedPattern> results, boolean isHighFirst) {

        Integer h1Idx = idx(tsToIdx, pHigh1);
        Integer h2Idx = idx(tsToIdx, pHigh2);
        Integer l1Idx = idx(tsToIdx, pLow1);
        Integer l2Idx = idx(tsToIdx, pLow2);
        Integer lastIdx = idx(tsToIdx, pLast);

        if (h1Idx < 0 || h2Idx < 0 || l1Idx < 0 || l2Idx < 0 || lastIdx < 0) return;

        double h1 = pHigh1.getValue();
        double h2 = pHigh2.getValue();
        double l1 = pLow1.getValue();
        double l2 = pLow2.getValue();

        int duration = lastIdx - Math.min(h1Idx, l1Idx);
        if (duration < MIN_PATTERN_BARS) return;

        double patternHeight = Math.max(h1, h2) - Math.min(l1, l2);
        double atrAtLast = lastIdx < atrArr.length ? atrArr[lastIdx] : 0;
        if (patternHeight < ATR_RANGE_FACTOR * atrAtLast) return;

        // Simplified C1 detection: look for reversal at last pivot
        int scanStart = Math.max(0, lastIdx - 2);
        int scanEnd = Math.min(bars.size() - 1, lastIdx + 2);
        for (int i = scanStart; i <= scanEnd; i++) {
            CandlestickPatternDetector.PatternResult rev = isHighFirst
                ? candlestickPatternDetector.detectBullish(bars, i)
                : candlestickPatternDetector.detectBearish(bars, i);
            if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

            double breakoutLevel = rev.breakoutLevel();
            for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                Bar bar = bars.get(j);
                boolean breakout = isHighFirst
                    ? bar.getHighPrice().doubleValue() > breakoutLevel
                    : bar.getLowPrice().doubleValue() < breakoutLevel;

                if (breakout) {
                    double entryPrice = bar.getOpenPrice().doubleValue();
                    double upperLevel = Math.max(h1, h2);
                    double lowerLevel = Math.min(l1, l2);
                    double stopLoss = isHighFirst
                        ? lowerLevel - 0.3 * atrAtLast
                        : upperLevel + 0.3 * atrAtLast;
                    double target = isHighFirst
                        ? entryPrice + patternHeight
                        : entryPrice - patternHeight;

                    double rsiAtP1 = l1Idx < rsiValues.length ? rsiValues[l1Idx] : 0;
                    double rsiAtP2 = l2Idx < rsiValues.length ? rsiValues[l2Idx] : 0;
                    double macdHistAtP1 = l1Idx < macdHistArr.length ? macdHistArr[l1Idx] : 0;
                    double macdHistAtP2 = l2Idx < macdHistArr.length ? macdHistArr[l2Idx] : 0;

                    results.add(DetectedPattern.builder()
                            .patternType("TRIANGLE")
                            .confirmationType("C1")
                            .bullish(isHighFirst)
                            .keyLevel(entryPrice)
                            .keyLevelTime(bar.getEndTime())
                            .entryPrice(entryPrice)
                            .stopLoss(stopLoss)
                            .ownTarget(target)
                            .patternHeight(patternHeight)
                            .atr(j < atrArr.length ? atrArr[j] : 0)
                            .rsiAtP1(rsiAtP1)
                            .rsiAtP2(rsiAtP2)
                            .macdHistAtP1(macdHistAtP1)
                            .macdHistAtP2(macdHistAtP2)
                            .stochRsiK(j < stochRsiK.length ? stochRsiK[j] : 0)
                            .dailyRsi(dailyInd.rsiAtTs(bar.getEndTime()))
                            .macdHistogram(j < macdHistArr.length ? macdHistArr[j] : 0)
                            .reversalPattern(rev.pattern().name())
                            .build());
                    return;
                }
            }
        }
    }

    private List<DetectedPattern> scanCandleClusterConfirmation(List<Bar> bars, double[] atrArr,
            double[] rsiValues, double[] macdHistArr, double[] stochRsiK, DailyIndicators dailyInd) {
        List<DetectedPattern> results = new ArrayList<>();

        for (int i = 0; i < bars.size() - 1; i++) {
            Bar current = bars.get(i);
            Bar next = bars.get(i + 1);

            // Check for reversal candles
            CandlestickPatternDetector.PatternResult bullRev = candlestickPatternDetector.detectBullish(bars, i);
            CandlestickPatternDetector.PatternResult bearRev = candlestickPatternDetector.detectBearish(bars, i);

            if (bullRev.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                double breakoutLevel = bullRev.breakoutLevel();
                for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                    if (bars.get(j).getHighPrice().doubleValue() > breakoutLevel) {
                        Bar entryBar = bars.get(j);
                        double entryPrice = entryBar.getOpenPrice().doubleValue();
                        double candleLow = current.getLowPrice().doubleValue();
                        double stopLoss = candleLow - 0.3 * (j < atrArr.length ? atrArr[j] : 10);

                        // Require minimum risk distance: at least 0.5×ATR (filters noise)
                        double localAtr = j < atrArr.length ? atrArr[j] : 10;
                        if (Math.abs(entryPrice - stopLoss) < 0.5 * localAtr) continue;
                        // Guard: SL must be on correct side
                        if (stopLoss >= entryPrice) continue;

                        results.add(DetectedPattern.builder()
                                .patternType("CANDLE_CLUSTER")
                                .confirmationType("CANDLE")
                                .bullish(true)
                                .keyLevel(entryPrice)
                                .keyLevelTime(entryBar.getEndTime())
                                .entryPrice(entryPrice)
                                .stopLoss(stopLoss)
                                .ownTarget(entryPrice + 3 * (j < atrArr.length ? atrArr[j] : 10))
                                .patternHeight(0)
                                .atr(j < atrArr.length ? atrArr[j] : 0)
                                .rsiAtP1(i < rsiValues.length ? rsiValues[i] : 0)
                                .rsiAtP2(j < rsiValues.length ? rsiValues[j] : 0)
                                .macdHistAtP1(i < macdHistArr.length ? macdHistArr[i] : 0)
                                .macdHistAtP2(j < macdHistArr.length ? macdHistArr[j] : 0)
                                .stochRsiK(j < stochRsiK.length ? stochRsiK[j] : 0)
                                .dailyRsi(dailyInd.rsiAtTs(entryBar.getEndTime()))
                                .macdHistogram(j < macdHistArr.length ? macdHistArr[j] : 0)
                                .reversalPattern(bullRev.pattern().name())
                                .build());
                        break;
                    }
                }
            }

            if (bearRev.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                double breakoutLevel = bearRev.breakoutLevel();
                for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                    if (bars.get(j).getLowPrice().doubleValue() < breakoutLevel) {
                        Bar entryBar = bars.get(j);
                        double entryPrice = entryBar.getOpenPrice().doubleValue();
                        double candleHigh = current.getHighPrice().doubleValue();
                        double stopLoss = candleHigh + 0.3 * (j < atrArr.length ? atrArr[j] : 10);

                        // Require minimum risk distance: at least 0.5×ATR (filters noise)
                        double localAtr = j < atrArr.length ? atrArr[j] : 10;
                        if (Math.abs(entryPrice - stopLoss) < 0.5 * localAtr) continue;
                        // Guard: SL must be on correct side
                        if (stopLoss <= entryPrice) continue;

                        results.add(DetectedPattern.builder()
                                .patternType("CANDLE_CLUSTER")
                                .confirmationType("CANDLE")
                                .bullish(false)
                                .keyLevel(entryPrice)
                                .keyLevelTime(entryBar.getEndTime())
                                .entryPrice(entryPrice)
                                .stopLoss(stopLoss)
                                .ownTarget(entryPrice - 3 * (j < atrArr.length ? atrArr[j] : 10))
                                .patternHeight(0)
                                .atr(j < atrArr.length ? atrArr[j] : 0)
                                .rsiAtP1(i < rsiValues.length ? rsiValues[i] : 0)
                                .rsiAtP2(j < rsiValues.length ? rsiValues[j] : 0)
                                .macdHistAtP1(i < macdHistArr.length ? macdHistArr[i] : 0)
                                .macdHistAtP2(j < macdHistArr.length ? macdHistArr[j] : 0)
                                .stochRsiK(j < stochRsiK.length ? stochRsiK[j] : 0)
                                .dailyRsi(dailyInd.rsiAtTs(entryBar.getEndTime()))
                                .macdHistogram(j < macdHistArr.length ? macdHistArr[j] : 0)
                                .reversalPattern(bearRev.pattern().name())
                                .build());
                        break;
                    }
                }
            }
        }
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV writer
    // ─────────────────────────────────────────────────────────────────────────

    private void writeCsv(List<ComboRow> rows, String csvPath) throws IOException {
        String header = "datetime,symbol,watching_pattern,watching_tf,confirm_pattern,confirm_tf,confirm_type," +
                "entry_price,stop_loss,watching_target,confirm_own_target,rr_watching,key_level," +
                "result,bars_to_result,pnl_pct,exit_price,exit_reason," +
                "rsi_at_p1,rsi_at_p2,macd_hist_at_p1,macd_hist_at_p2," +
                "stoch_rsi_k_15m,daily_rsi,watching_pattern_height,confirm_pattern_height\n";
        try (FileWriter fw = new FileWriter(csvPath)) {
            fw.write(header);
            for (ComboRow r : rows) {
                fw.write(r.toCsvRow());
                fw.write("\n");
            }
        }
        log.info("[Combo] CSV written: {} rows → {}", rows.size(), csvPath);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper methods (copied from DoubleTopBottomBacktestService)
    // ─────────────────────────────────────────────────────────────────────────

    private double[] computeAtr(List<Bar> bars, int period) {
        int n = bars.size();
        double[] tr = new double[n];
        double[] atr = new double[n];
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            double high = b.getHighPrice().doubleValue();
            double low = b.getLowPrice().doubleValue();
            double prevClose = (i == 0) ? b.getClosePrice().doubleValue() : bars.get(i - 1).getClosePrice().doubleValue();
            tr[i] = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            if (i == 0) {
                atr[i] = tr[i];
            } else if (i < period) {
                atr[i] = ((atr[i - 1] * i) + tr[i]) / (i + 1);
            } else {
                atr[i] = ((atr[i - 1] * (period - 1)) + tr[i]) / period;
            }
        }
        return atr;
    }

    private double[] computeStochRsiK(double[] rsiValues) {
        int n = rsiValues.length;
        double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - 13);
            double minR = rsiValues[start], maxR = rsiValues[start];
            for (int j = start + 1; j <= i; j++) {
                if (rsiValues[j] < minR) minR = rsiValues[j];
                if (rsiValues[j] > maxR) maxR = rsiValues[j];
            }
            double range = maxR - minR;
            raw[i] = range > 0 ? (rsiValues[i] - minR) / range : 0.0;
        }
        double[] k = new double[n];
        for (int i = 0; i < n; i++) {
            k[i] = i < 2 ? raw[i] : (raw[i] + raw[i - 1] + raw[i - 2]) / 3.0;
        }
        return k;
    }

    private double[] computeStochRsiD(double[] k) {
        int n = k.length;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = i < 2 ? k[i] : (k[i] + k[i - 1] + k[i - 2]) / 3.0;
        }
        return d;
    }

    private double safeDouble(org.ta4j.core.num.Num num) {
        try {
            return num == null || num.isNaN() ? 0.0 : num.doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    private DailyIndicators computeDailyIndicators(BarSeries dailySeries) {
        if (dailySeries == null || dailySeries.isEmpty()) {
            return new DailyIndicators(new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        }
        int m = dailySeries.getBarCount();
        ClosePriceIndicator dClose = new ClosePriceIndicator(dailySeries);
        MACDIndicator dMacd = new MACDIndicator(dClose, MACD_SHORT, MACD_LONG);
        EMAIndicator dMacdSignal = new EMAIndicator(dMacd, MACD_SIGNAL_PERIOD);
        RSIIndicator dRsi = new RSIIndicator(dClose, RSI_PERIOD);

        double[] dRsiValues = new double[m];
        for (int i = 0; i < m; i++) dRsiValues[i] = safeDouble(dRsi.getValue(i));
        double[] dStochK = computeStochRsiK(dRsiValues);

        TreeMap<Instant, Double> macdHistMap = new TreeMap<>();
        TreeMap<Instant, Double> rsiMap = new TreeMap<>();
        TreeMap<Instant, Double> stochRsiKMap = new TreeMap<>();

        for (int i = 0; i < m; i++) {
            Instant ts = dailySeries.getBar(i).getEndTime();
            double macdVal = safeDouble(dMacd.getValue(i));
            double macdSig = safeDouble(dMacdSignal.getValue(i));
            macdHistMap.put(ts, macdVal - macdSig);
            rsiMap.put(ts, dRsiValues[i]);
            stochRsiKMap.put(ts, dStochK[i]);
        }
        return new DailyIndicators(macdHistMap, rsiMap, stochRsiKMap);
    }

    private int idx(Map<Instant, Integer> tsToIdx, ZigZagPoint p) {
        Integer i = tsToIdx.get(p.getTimestamp());
        return i != null ? i : -1;
    }

    private Map<Instant, Integer> buildTsToIdx(List<Bar> bars) {
        Map<Instant, Integer> map = new HashMap<>();
        for (int i = 0; i < bars.size(); i++) {
            map.put(bars.get(i).getEndTime(), i);
        }
        return map;
    }

    private List<Bar> toList(BarSeries series) {
        List<Bar> list = new ArrayList<>();
        for (int i = 0; i < series.getBarCount(); i++) {
            list.add(series.getBar(i));
        }
        return list;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

    @lombok.Data
    @lombok.Builder
    public static class ComboRow {
        String  symbol;
        String  watchingPattern;
        String  watchingTf;
        String  confirmPattern;
        String  confirmTf;
        String  confirmType;
        Instant entryTime;
        double  entryPrice;
        double  stopLoss;
        double  watchingTarget;
        double  confirmOwnTarget;
        double  rrWatching;
        double  keyLevel;
        String  result;
        int     barsToResult;
        double  pnlPct;
        double  exitPrice;
        String  exitReason;
        double  rsiAtP1;
        double  rsiAtP2;
        double  macdHistAtP1;
        double  macdHistAtP2;
        double  stochRsiK15m;
        double  dailyRsi;
        double  watchingPatternHeight;
        double  confirmPatternHeight;

        public String toCsvRow() {
            return String.format(Locale.US,
                "%s,%s,%s,%s,%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%d,%.2f,%.2f,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f",
                entryTime, symbol, watchingPattern, watchingTf, confirmPattern, confirmTf, confirmType,
                entryPrice, stopLoss, watchingTarget, confirmOwnTarget, rrWatching, keyLevel,
                result, barsToResult, pnlPct, exitPrice, exitReason,
                rsiAtP1, rsiAtP2, macdHistAtP1, macdHistAtP2, stochRsiK15m, dailyRsi,
                watchingPatternHeight, confirmPatternHeight);
        }
    }

    private record DailyIndicators(
            TreeMap<Instant, Double> macdHistMap,
            TreeMap<Instant, Double> rsiMap,
            TreeMap<Instant, Double> stochRsiKMap) {

        double macdHistAtTs(Instant ts) {
            return floorValue(macdHistMap, ts);
        }

        double rsiAtTs(Instant ts) {
            return floorValue(rsiMap, ts);
        }

        double stochRsiKAtTs(Instant ts) {
            return floorValue(stochRsiKMap, ts);
        }

        private double floorValue(TreeMap<Instant, Double> map, Instant ts) {
            if (map.isEmpty()) return 0.0;
            Map.Entry<Instant, Double> entry = map.floorEntry(ts);
            return entry != null ? entry.getValue() : 0.0;
        }
    }
}
