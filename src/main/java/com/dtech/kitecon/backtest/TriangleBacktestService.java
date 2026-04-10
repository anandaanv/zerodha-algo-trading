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
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;

import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;
import java.util.*;

/**
 * Triangle pattern backtest service.
 *
 * Detects 3 triangle types (Ascending, Descending, Symmetric) using 5 ZigZag pivots:
 *   High-first: H1, L1, H2, L2, H3  (p0=H, p1=L, p2=H, p3=L, p4=H)
 *   Low-first:  L1, H1, L2, H2, L3  (p0=L, p1=H, p2=L, p3=H, p4=L)
 *
 * Entry types:
 *   C1 – reversal candle at 6th pivot trendline touch + breakout confirmation
 *   C2 – trendline breakout → retest → reversal candle → entry
 *
 * Quality filters:
 *   - Pattern height ≥ 2× ATR (timeframe-agnostic)
 *   - Pattern duration ≥ 5 bars
 *   - Last pivot must be within 1 ATR of its projected trendline
 *   - RR ratio ≥ 2.0
 *
 * Additional confluence:
 *   - Prior impulse direction (pivot before the triangle)
 *   - EDT signal: MACD histogram divergence at the two key pivots (tagged in pattern name)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TriangleBacktestService {

    private static final int    MACD_SHORT         = 12;
    private static final int    MACD_LONG          = 26;
    private static final int    MACD_SIGNAL_PERIOD = 9;
    private static final int    RSI_PERIOD         = 14;
    private static final int    BB_PERIOD          = 20;
    private static final int    ATR_PERIOD         = 14;
    private static final double TRADE_OVERHEAD_PCT = 0.1;
    private static final double ATR_FLAT_FACTOR    = 0.5;  // |H2-H1| < 0.5×ATR → "flat" trendline
    private static final double ATR_RANGE_FACTOR   = 2.0;  // pattern height ≥ 2×ATR
    private static final int    MIN_PATTERN_BARS   = 5;

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final CandlestickPatternDetector candlestickPatternDetector = new CandlestickPatternDetector();

    enum TriangleType { ASCENDING, DESCENDING, SYMMETRIC, EXPANDING }

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry points
    // ─────────────────────────────────────────────────────────────────────────

    public void runAndWriteCsv(String symbol, String csvPath) throws IOException {
        List<TradeSetup> setups = runForSymbol(symbol);
        writeCsv(setups, csvPath);
    }

    public void runMultipleAndWriteCsv(List<String> symbols, String csvPath) throws IOException {
        List<TradeSetup> all = new ArrayList<>();
        int failed = 0;
        for (String symbol : symbols) {
            try {
                all.addAll(runForSymbol(symbol));
            } catch (Exception e) {
                log.warn("[Triangle] Skipping {} due to error: {}", symbol, e.getMessage());
                failed++;
            }
        }
        log.info("[Triangle] Total setups: {} ({} symbol(s) failed)", all.size(), failed);
        writeCsv(all, csvPath);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Per-symbol scan
    // ─────────────────────────────────────────────────────────────────────────

    private List<TradeSetup> runForSymbol(String symbol) {
        log.info("[Triangle] Starting for {}", symbol);

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) { log.error("[Triangle] Instrument not found: {}", symbol); return new ArrayList<>(); }

        BarSeries hourlySeries = zigZagService.getBarSeries(symbol, instrument, Interval.OneHour);
        BarSeries dailySeries  = zigZagService.getBarSeries(symbol, instrument, Interval.Day);
        if (hourlySeries == null || hourlySeries.isEmpty()) { log.warn("[Triangle] No hourly data for {}", symbol); return new ArrayList<>(); }

        ZigZagParams params = zigZagService.resolveParams(symbol, Interval.OneHour);
        List<ZigZagPoint> pivots = zigZagService.detect(hourlySeries, params);
        log.info("[Triangle] {} pivots for {}", pivots.size(), symbol);

        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < hourlySeries.getBarCount(); i++) bars.add(hourlySeries.getBar(i));

        Map<Instant, Integer> tsToIdx = new HashMap<>();
        for (int i = 0; i < bars.size(); i++) tsToIdx.put(bars.get(i).getEndTime(), i);

        int n = hourlySeries.getBarCount();
        ClosePriceIndicator close       = new ClosePriceIndicator(hourlySeries);
        MACDIndicator macd              = new MACDIndicator(close, MACD_SHORT, MACD_LONG);
        EMAIndicator macdSignalLine     = new EMAIndicator(macd, MACD_SIGNAL_PERIOD);
        RSIIndicator rsi                = new RSIIndicator(close, RSI_PERIOD);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(new EMAIndicator(close, BB_PERIOD));
        StandardDeviationIndicator stdDev      = new StandardDeviationIndicator(close, BB_PERIOD);
        BollingerBandsUpperIndicator bbUpper   = new BollingerBandsUpperIndicator(bbMiddle, stdDev);
        BollingerBandsLowerIndicator bbLower   = new BollingerBandsLowerIndicator(bbMiddle, stdDev);

        double[] rsiValues   = new double[n];
        for (int i = 0; i < n; i++) rsiValues[i] = safeDouble(rsi.getValue(i));
        double[] stochRsiK   = computeStochRsiK(rsiValues);
        double[] stochRsiD   = computeStochRsiD(stochRsiK);
        double[] atrArr      = computeAtr(bars, ATR_PERIOD);
        double[] bbWidths    = new double[n];
        for (int i = 0; i < n; i++) {
            double mid = safeDouble(bbMiddle.getValue(i));
            double up  = safeDouble(bbUpper.getValue(i));
            double lo  = safeDouble(bbLower.getValue(i));
            bbWidths[i] = mid > 0 ? (up - lo) / mid : 0;
        }
        double[] macdHistArr   = new double[n];
        double[] macdSignalArr = new double[n];
        for (int i = 0; i < n; i++) {
            double mv = safeDouble(macd.getValue(i)), sv = safeDouble(macdSignalLine.getValue(i));
            macdHistArr[i] = mv - sv;
            macdSignalArr[i] = sv;
        }

        DailyIndicators dailyIndicators = computeDailyIndicators(dailySeries);

        List<TradeSetup> results = new ArrayList<>();
        for (int i = 4; i < pivots.size(); i++) {
            ZigZagPoint p0 = pivots.get(i - 4), p1 = pivots.get(i - 3),
                        p2 = pivots.get(i - 2), p3 = pivots.get(i - 1), p4 = pivots.get(i);
            ZigZagPoint priorPivot = i >= 5 ? pivots.get(i - 5) : null;

            // High-first: H L H L H
            if (p0.isHigh() && p1.isLow() && p2.isHigh() && p3.isLow() && p4.isHigh())
                detectTriangle(p0, p1, p2, p3, p4, priorPivot, true,
                        symbol, bars, tsToIdx, rsiValues, atrArr, stochRsiK, stochRsiD,
                        bbWidths, macdHistArr, macdSignalArr, macd, macdSignalLine, dailyIndicators, results);

            // Low-first: L H L H L
            if (p0.isLow() && p1.isHigh() && p2.isLow() && p3.isHigh() && p4.isLow())
                detectTriangle(p0, p1, p2, p3, p4, priorPivot, false,
                        symbol, bars, tsToIdx, rsiValues, atrArr, stochRsiK, stochRsiD,
                        bbWidths, macdHistArr, macdSignalArr, macd, macdSignalLine, dailyIndicators, results);
        }

        log.info("[Triangle] {} setups for {}", results.size(), symbol);
        return results;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Triangle detection, classification, and entry dispatch
    // ─────────────────────────────────────────────────────────────────────────

    private void detectTriangle(
            ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2, ZigZagPoint p3, ZigZagPoint p4,
            ZigZagPoint priorPivot, boolean highFirst,
            String symbol, List<Bar> bars, Map<Instant, Integer> tsToIdx,
            double[] rsiValues, double[] atrArr, double[] stochRsiK, double[] stochRsiD,
            double[] bbWidths, double[] macdHistArr, double[] macdSignalArr,
            MACDIndicator macd, EMAIndicator macdSignalLine,
            DailyIndicators dailyIndicators, List<TradeSetup> results) {

        // highFirst: p0=H1, p1=L1, p2=H2, p3=L2, p4=H3
        // !highFirst: p0=L1, p1=H1, p2=L2, p3=H2, p4=L3
        double h1, h2, l1, l2;
        int h1Idx, h2Idx, l1Idx, l2Idx, lastPivotIdx;

        if (highFirst) {
            h1 = p0.getValue(); h2 = p2.getValue(); l1 = p1.getValue(); l2 = p3.getValue();
            h1Idx = idx(tsToIdx, p0); h2Idx = idx(tsToIdx, p2);
            l1Idx = idx(tsToIdx, p1); l2Idx = idx(tsToIdx, p3);
            lastPivotIdx = idx(tsToIdx, p4);
        } else {
            l1 = p0.getValue(); l2 = p2.getValue(); h1 = p1.getValue(); h2 = p3.getValue();
            l1Idx = idx(tsToIdx, p0); l2Idx = idx(tsToIdx, p2);
            h1Idx = idx(tsToIdx, p1); h2Idx = idx(tsToIdx, p3);
            lastPivotIdx = idx(tsToIdx, p4);
        }

        if (h1Idx < 0 || h2Idx < 0 || l1Idx < 0 || l2Idx < 0 || lastPivotIdx < 0) return;

        // Quality 1: pattern duration
        int firstPivotIdx = Math.min(Math.min(h1Idx, h2Idx), Math.min(l1Idx, l2Idx));
        if (lastPivotIdx - firstPivotIdx < MIN_PATTERN_BARS) return;

        // Quality 2: ATR-based height
        double atrAtStart  = atrArr[firstPivotIdx];
        double patternHeight = Math.max(h1, h2) - Math.min(l1, l2);
        if (patternHeight < ATR_RANGE_FACTOR * atrAtStart) return;

        // Trendline slopes (price per bar index unit)
        double upperSlope = (h2 - h1) / (h2Idx - h1Idx);
        double lowerSlope = (l2 - l1) / (l2Idx - l1Idx);

        // Classify
        boolean upperFlat    = Math.abs(h2 - h1) < ATR_FLAT_FACTOR * atrAtStart;
        boolean lowerFlat    = Math.abs(l2 - l1) < ATR_FLAT_FACTOR * atrAtStart;
        boolean upperFalling = h2 < h1 - ATR_FLAT_FACTOR * atrAtStart;
        boolean upperRising  = h2 > h1 + ATR_FLAT_FACTOR * atrAtStart;
        boolean lowerRising  = l2 > l1 + ATR_FLAT_FACTOR * atrAtStart;
        boolean lowerFalling = l2 < l1 - ATR_FLAT_FACTOR * atrAtStart;

        TriangleType type;
        if      (upperFalling && lowerRising)             type = TriangleType.SYMMETRIC;
        else if (upperRising  && lowerFalling)            type = TriangleType.EXPANDING;
        else if (lowerRising  && (upperFlat || upperFalling)) type = TriangleType.ASCENDING;
        else if (upperFalling && (lowerFlat || lowerFalling)) type = TriangleType.DESCENDING;
        else return;

        if (type == TriangleType.EXPANDING) return;

        // Verify last pivot is consistent with its trendline (within 1 ATR)
        if (highFirst) {
            double expectedH3 = h1 + upperSlope * (lastPivotIdx - h1Idx);
            if (Math.abs(p4.getValue() - expectedH3) > atrAtStart) return;
        } else {
            double expectedL3 = l1 + lowerSlope * (lastPivotIdx - l1Idx);
            if (Math.abs(p4.getValue() - expectedL3) > atrAtStart) return;
        }

        // Prior impulse direction
        boolean priorImpulseUp = true;
        if (priorPivot != null) {
            if (highFirst) priorImpulseUp = (h1 - priorPivot.getValue()) > 0.5 * patternHeight;
            else           priorImpulseUp = !((priorPivot.getValue() - l1) > 0.5 * patternHeight);
        }

        boolean bullish = switch (type) {
            case ASCENDING  -> true;
            case DESCENDING -> false;
            case SYMMETRIC  -> priorImpulseUp;
            default         -> true;
        };

        // RSI and MACD at the two key pivots (lows for bullish, highs for bearish)
        double rsiAtP1, rsiAtP2, macdHistAtP1, macdHistAtP2;
        if (bullish) {
            rsiAtP1      = l1Idx < rsiValues.length    ? rsiValues[l1Idx]    : 0;
            rsiAtP2      = l2Idx < rsiValues.length    ? rsiValues[l2Idx]    : 0;
            macdHistAtP1 = l1Idx < macdHistArr.length  ? macdHistArr[l1Idx]  : 0;
            macdHistAtP2 = l2Idx < macdHistArr.length  ? macdHistArr[l2Idx]  : 0;
        } else {
            rsiAtP1      = h1Idx < rsiValues.length    ? rsiValues[h1Idx]    : 0;
            rsiAtP2      = h2Idx < rsiValues.length    ? rsiValues[h2Idx]    : 0;
            macdHistAtP1 = h1Idx < macdHistArr.length  ? macdHistArr[h1Idx]  : 0;
            macdHistAtP2 = h2Idx < macdHistArr.length  ? macdHistArr[h2Idx]  : 0;
        }

        // EDT signal: lower low / higher high but MACD diverging
        boolean edtDivergence = bullish
                ? (l2 < l1 && macdHistAtP2 > macdHistAtP1)
                : (h2 > h1 && macdHistAtP2 < macdHistAtP1);

        String triangleName = "TRIANGLE_" + type.name() + (edtDivergence ? "_EDT" : "");

        // ── Entry scans ─────────────────────────────────────────────────────
        detectC1(triangleName, bullish, h1, h2, l1, l2, h1Idx, l1Idx,
                upperSlope, lowerSlope, patternHeight, atrAtStart, lastPivotIdx,
                symbol, bars, macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                dailyIndicators, macdHistArr, macdSignalArr,
                rsiAtP1, rsiAtP2, macdHistAtP1, macdHistAtP2, results);

        detectC2(triangleName, bullish, h1, h2, l1, l2, h1Idx, l1Idx,
                upperSlope, lowerSlope, patternHeight, atrAtStart, lastPivotIdx,
                symbol, bars, macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                dailyIndicators, macdHistArr, macdSignalArr,
                rsiAtP1, rsiAtP2, macdHistAtP1, macdHistAtP2, results);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C1: reversal candle at the 6th trendline touch (opposite line after 5th pivot)
    // ─────────────────────────────────────────────────────────────────────────

    private void detectC1(
            String patternName, boolean bullish,
            double h1, double h2, double l1, double l2, int h1Idx, int l1Idx,
            double upperSlope, double lowerSlope, double patternHeight, double atr, int lastPivotIdx,
            String symbol, List<Bar> bars, MACDIndicator macd, EMAIndicator macdSignalLine,
            double[] stochRsiK, double[] stochRsiD, double[] bbWidths, double[] atrArr,
            DailyIndicators dailyIndicators, double[] macdHistArr, double[] macdSignalArr,
            double rsiAtP1, double rsiAtP2, double macdHistAtP1, double macdHistAtP2,
            List<TradeSetup> results) {

        int scanLimit = Math.min(bars.size() - 2, lastPivotIdx + 25);

        for (int i = lastPivotIdx; i <= scanLimit; i++) {
            Bar b = bars.get(i);
            double upperAt = h1 + upperSlope * (i - h1Idx);
            double lowerAt = l1 + lowerSlope * (i - l1Idx);

            // Invalidation: price closed beyond triangle boundary
            double closePrice = b.getClosePrice().doubleValue();
            if (closePrice > upperAt + 1.5 * atr) break;
            if (closePrice < lowerAt - 1.5 * atr) break;

            // Check if bar is near the target trendline
            boolean nearTarget;
            if (bullish) {
                // Waiting for price to touch LOWER trendline (L3 area)
                nearTarget = b.getLowPrice().doubleValue()  <= lowerAt + 0.5 * atr
                          && b.getLowPrice().doubleValue()  >= lowerAt - atr;
            } else {
                // Waiting for price to touch UPPER trendline (H3 area for low-first)
                nearTarget = b.getHighPrice().doubleValue() >= upperAt - 0.5 * atr
                          && b.getHighPrice().doubleValue() <= upperAt + atr;
            }
            if (!nearTarget) continue;

            // Detect reversal candle
            CandlestickPatternDetector.PatternResult rev = bullish
                    ? candlestickPatternDetector.detectBullish(bars, i)
                    : candlestickPatternDetector.detectBearish(bars, i);
            if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

            // Scan for breakout
            for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                Bar bj = bars.get(j);
                boolean fired = bullish
                        ? bj.getHighPrice().doubleValue() > rev.breakoutLevel()
                        : bj.getLowPrice().doubleValue()  < rev.breakoutLevel();
                if (!fired) continue;

                double entryPrice = bj.getOpenPrice().doubleValue();
                double stopLoss   = bullish ? lowerAt - 0.3 * atr : upperAt + 0.3 * atr;
                double target     = bullish ? entryPrice + patternHeight : entryPrice - patternHeight;

                buildAndAddSetup(patternName, "C1", symbol,
                        bullish ? l1 : h1, bullish ? l2 : h2,
                        bullish ? lowerAt : upperAt,
                        0, patternHeight, bars, i, j - 1, j,
                        entryPrice, stopLoss, target,
                        macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                        dailyIndicators, rev.pattern(), macdHistArr, macdSignalArr,
                        rsiAtP1, rsiAtP2, macdHistAtP1, macdHistAtP2, bullish, results);
                break;
            }
            break; // only first trendline touch
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // C2: trendline breakout → retest → reversal candle → entry
    // ─────────────────────────────────────────────────────────────────────────

    private void detectC2(
            String patternName, boolean bullish,
            double h1, double h2, double l1, double l2, int h1Idx, int l1Idx,
            double upperSlope, double lowerSlope, double patternHeight, double atr, int lastPivotIdx,
            String symbol, List<Bar> bars, MACDIndicator macd, EMAIndicator macdSignalLine,
            double[] stochRsiK, double[] stochRsiD, double[] bbWidths, double[] atrArr,
            DailyIndicators dailyIndicators, double[] macdHistArr, double[] macdSignalArr,
            double rsiAtP1, double rsiAtP2, double macdHistAtP1, double macdHistAtP2,
            List<TradeSetup> results) {

        // Scan for breakout beyond the expected trendline
        int breakoutIdx = -1;
        int scanEnd = Math.min(bars.size() - 2, lastPivotIdx + 40);

        for (int i = lastPivotIdx + 1; i <= scanEnd; i++) {
            Bar b = bars.get(i);
            double upperAt = h1 + upperSlope * (i - h1Idx);
            double lowerAt = l1 + lowerSlope * (i - l1Idx);
            if (bullish  && b.getClosePrice().doubleValue() > upperAt) { breakoutIdx = i; break; }
            if (!bullish && b.getClosePrice().doubleValue() < lowerAt) { breakoutIdx = i; break; }
        }
        if (breakoutIdx < 0 || breakoutIdx + 1 >= bars.size()) return;

        // Scan for retest of the broken trendline
        int retestEnd = Math.min(bars.size() - 2, breakoutIdx + 30);
        for (int i = breakoutIdx + 1; i <= retestEnd; i++) {
            Bar b = bars.get(i);
            double brokenAt = bullish
                    ? h1 + upperSlope * (i - h1Idx)
                    : l1 + lowerSlope * (i - l1Idx);

            boolean isRetest = bullish
                    ? b.getLowPrice().doubleValue()  <= brokenAt + atr && b.getLowPrice().doubleValue()  >= brokenAt - atr
                    : b.getHighPrice().doubleValue() >= brokenAt - atr && b.getHighPrice().doubleValue() <= brokenAt + atr;
            if (!isRetest) continue;

            // Reversal candle at retest
            CandlestickPatternDetector.PatternResult rev = bullish
                    ? candlestickPatternDetector.detectBullish(bars, i)
                    : candlestickPatternDetector.detectBearish(bars, i);
            if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

            for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                Bar bj = bars.get(j);
                boolean fired = bullish
                        ? bj.getHighPrice().doubleValue() > rev.breakoutLevel()
                        : bj.getLowPrice().doubleValue()  < rev.breakoutLevel();
                if (!fired) continue;

                double entryPrice = bj.getOpenPrice().doubleValue();
                double stopLoss   = bullish ? brokenAt - 0.3 * atr : brokenAt + 0.3 * atr;
                double target     = bullish ? entryPrice + patternHeight : entryPrice - patternHeight;

                buildAndAddSetup(patternName, "C2", symbol,
                        bullish ? l1 : h1, bullish ? l2 : h2, brokenAt,
                        0, patternHeight, bars, i, j - 1, j,
                        entryPrice, stopLoss, target,
                        macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                        dailyIndicators, rev.pattern(), macdHistArr, macdSignalArr,
                        rsiAtP1, rsiAtP2, macdHistAtP1, macdHistAtP2, bullish, results);
                break;
            }
            break; // only first retest
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build trade setup + simulate exit (identical logic to DoubleTopBottomBacktestService)
    // ─────────────────────────────────────────────────────────────────────────

    private void buildAndAddSetup(
            String patternType, String confirmationType,
            String symbol, double level1Price, double level2Price, double neckline,
            double fibRetrace, double patternHeight,
            List<Bar> bars, int revCandleIdx, int sigCandleIdx, int entryBarIdx,
            double entryPrice, double stopLoss, double target,
            MACDIndicator macd, EMAIndicator macdSignalLine,
            double[] stochRsiK, double[] stochRsiD, double[] bbWidths, double[] atrArr,
            DailyIndicators dailyIndicators,
            CandlestickPatternDetector.CandlePattern reversalPattern,
            double[] macdHistArr, double[] macdSignalArr,
            double rsiAtP1, double rsiAtP2, double macdHistAtP1, double macdHistAtP2,
            boolean bullish, List<TradeSetup> results) {

        if (entryBarIdx >= bars.size()) return;
        if (stopLoss <= 0 || entryPrice <= 0) return;

        double riskPct   = Math.abs(entryPrice - stopLoss) / entryPrice * 100.0;
        double rewardPct = Math.abs(target - entryPrice)   / entryPrice * 100.0;
        double rrRatio   = riskPct > 0 ? rewardPct / riskPct : 0;
        if (rrRatio < 2.0) return;

        Bar revCandle = bars.get(Math.max(0, Math.min(revCandleIdx, bars.size() - 1)));
        Bar sigCandle = bars.get(Math.max(0, Math.min(sigCandleIdx, bars.size() - 1)));
        Bar entryBar  = bars.get(entryBarIdx);

        double bbWidth = sigCandleIdx < bbWidths.length ? bbWidths[sigCandleIdx] : 0;
        boolean bbContracting = bbWidth < avgBbWidth(bbWidths, sigCandleIdx, 5);
        double macdVal  = sigCandleIdx < bars.size() ? safeDouble(macd.getValue(sigCandleIdx)) : 0;
        double macdSig  = sigCandleIdx < bars.size() ? safeDouble(macdSignalLine.getValue(sigCandleIdx)) : 0;
        double stochKVal = sigCandleIdx < stochRsiK.length ? stochRsiK[sigCandleIdx] : 0;
        double stochDVal = sigCandleIdx < stochRsiD.length ? stochRsiD[sigCandleIdx] : 0;

        Instant entryTs = entryBar.getEndTime();
        double dailyMacdHist  = dailyIndicators.macdHistAtTs(entryTs);
        double dailyRsiVal    = dailyIndicators.rsiAtTs(entryTs);
        double dailyStochRsiK = dailyIndicators.stochRsiKAtTs(entryTs);

        String result = "OPEN"; int barsToResult = 0;
        double pnlPct = 0.0, exitPrice = 0.0; String exitReason = "OPEN";

        for (int k = entryBarIdx + 1; k < bars.size(); k++) {
            Bar b = bars.get(k);
            // SL check
            if (bullish  && b.getLowPrice().doubleValue()  <= stopLoss) {
                result = "STOP_HIT"; exitReason = "STOP_HIT";
                exitPrice = k + 1 < bars.size() ? bars.get(k+1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                pnlPct = (exitPrice - entryPrice) / entryPrice * 100.0 - TRADE_OVERHEAD_PCT;
                barsToResult = k - entryBarIdx; break;
            }
            if (!bullish && b.getHighPrice().doubleValue() >= stopLoss) {
                result = "STOP_HIT"; exitReason = "STOP_HIT";
                exitPrice = k + 1 < bars.size() ? bars.get(k+1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                pnlPct = (entryPrice - exitPrice) / entryPrice * 100.0 - TRADE_OVERHEAD_PCT;
                barsToResult = k - entryBarIdx; break;
            }
            // Candlestick reversal exit (only after 3 bars)
            if (k >= entryBarIdx + 3) {
                CandlestickPatternDetector.PatternResult exitCandle = bullish
                        ? candlestickPatternDetector.detectBearish(bars, k)
                        : candlestickPatternDetector.detectBullish(bars, k);
                if (exitCandle.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                    exitReason = bullish ? "BEARISH_CANDLE" : "BULLISH_CANDLE";
                    exitPrice = k + 1 < bars.size() ? bars.get(k+1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                    pnlPct = bullish
                            ? (exitPrice - entryPrice) / entryPrice * 100.0 - TRADE_OVERHEAD_PCT
                            : (entryPrice - exitPrice) / entryPrice * 100.0 - TRADE_OVERHEAD_PCT;
                    result = pnlPct > 0 ? "WIN" : "LOSS";
                    barsToResult = k - entryBarIdx; break;
                }
            }
        }

        results.add(TradeSetup.builder()
                .datetime(entryTs.toString()).symbol(symbol).patternType(patternType).confirmationType(confirmationType)
                .level1Price(level1Price).level2Price(level2Price).neckline(neckline)
                .fibRetracePct(fibRetrace).patternHeight(patternHeight)
                .reversalPattern(reversalPattern.name())
                .revCandleOpen(revCandle.getOpenPrice().doubleValue()).revCandleHigh(revCandle.getHighPrice().doubleValue())
                .revCandleLow(revCandle.getLowPrice().doubleValue()).revCandleClose(revCandle.getClosePrice().doubleValue())
                .sigCandleOpen(sigCandle.getOpenPrice().doubleValue()).sigCandleHigh(sigCandle.getHighPrice().doubleValue())
                .sigCandleLow(sigCandle.getLowPrice().doubleValue()).sigCandleClose(sigCandle.getClosePrice().doubleValue())
                .sigCandleVolume(sigCandle.getVolume().doubleValue())
                .entryPrice(entryPrice).stopLoss(stopLoss).target(target)
                .riskPct(riskPct).rewardPct(rewardPct).rrRatio(rrRatio)
                .macdHistogram(macdVal - macdSig).macdSignal(macdSig)
                .stochRsiK(stochKVal).stochRsiD(stochDVal)
                .bbWidth(bbWidth).bbContracting(bbContracting ? "CONTRACTING" : "EXPANDING")
                .dailyMacdHistogram(dailyMacdHist).dailyRsi(dailyRsiVal).dailyStochRsiK(dailyStochRsiK)
                .rsiAtP1(rsiAtP1).rsiAtP2(rsiAtP2).macdHistAtP1(macdHistAtP1).macdHistAtP2(macdHistAtP2)
                .result(result).barsToResult(barsToResult).pnlPct(pnlPct).exitPrice(exitPrice).exitReason(exitReason)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers (same as DoubleTopBottomBacktestService)
    // ─────────────────────────────────────────────────────────────────────────

    private int idx(Map<Instant, Integer> tsToIdx, ZigZagPoint p) {
        Integer i = tsToIdx.get(p.getTimestamp()); return i != null ? i : -1;
    }

    private double[] computeStochRsiK(double[] rsiValues) {
        int n = rsiValues.length; double[] raw = new double[n];
        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - 13); double minR = rsiValues[start], maxR = rsiValues[start];
            for (int j = start + 1; j <= i; j++) { if (rsiValues[j] < minR) minR = rsiValues[j]; if (rsiValues[j] > maxR) maxR = rsiValues[j]; }
            double range = maxR - minR; raw[i] = range > 0 ? (rsiValues[i] - minR) / range : 0.0;
        }
        double[] k = new double[n];
        for (int i = 0; i < n; i++) k[i] = i < 2 ? raw[i] : (raw[i] + raw[i-1] + raw[i-2]) / 3.0;
        return k;
    }

    private double[] computeStochRsiD(double[] k) {
        int n = k.length; double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = i < 2 ? k[i] : (k[i] + k[i-1] + k[i-2]) / 3.0;
        return d;
    }

    private double[] computeAtr(List<Bar> bars, int period) {
        int n = bars.size(); double[] tr = new double[n], atr = new double[n];
        for (int i = 0; i < n; i++) {
            double high = bars.get(i).getHighPrice().doubleValue(), low = bars.get(i).getLowPrice().doubleValue();
            double prevClose = i == 0 ? bars.get(i).getClosePrice().doubleValue() : bars.get(i-1).getClosePrice().doubleValue();
            tr[i] = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            atr[i] = i == 0 ? tr[i] : i < period ? ((atr[i-1]*i)+tr[i])/(i+1) : ((atr[i-1]*(period-1))+tr[i])/period;
        }
        return atr;
    }

    private double avgBbWidth(double[] w, int idx, int lb) {
        if (idx < lb) return w[idx]; double sum = 0;
        for (int i = idx - lb; i < idx; i++) sum += w[i]; return sum / lb;
    }

    private double safeDouble(org.ta4j.core.num.Num num) {
        try { return num == null || num.isNaN() ? 0.0 : num.doubleValue(); } catch (Exception e) { return 0.0; }
    }

    private DailyIndicators computeDailyIndicators(BarSeries dailySeries) {
        if (dailySeries == null || dailySeries.isEmpty())
            return new DailyIndicators(new TreeMap<>(), new TreeMap<>(), new TreeMap<>());
        int m = dailySeries.getBarCount();
        ClosePriceIndicator dClose = new ClosePriceIndicator(dailySeries);
        MACDIndicator dMacd = new MACDIndicator(dClose, MACD_SHORT, MACD_LONG);
        EMAIndicator dMacdSignal = new EMAIndicator(dMacd, MACD_SIGNAL_PERIOD);
        RSIIndicator dRsi = new RSIIndicator(dClose, RSI_PERIOD);
        double[] dRsiValues = new double[m];
        for (int i = 0; i < m; i++) dRsiValues[i] = safeDouble(dRsi.getValue(i));
        double[] dStochK = computeStochRsiK(dRsiValues);
        TreeMap<Instant, Double> macdHistMap = new TreeMap<>(), rsiMap = new TreeMap<>(), stochMap = new TreeMap<>();
        for (int i = 0; i < m; i++) {
            Instant ts = dailySeries.getBar(i).getEndTime();
            double mv = safeDouble(dMacd.getValue(i)), sv = safeDouble(dMacdSignal.getValue(i));
            macdHistMap.put(ts, mv - sv); rsiMap.put(ts, dRsiValues[i]); stochMap.put(ts, dStochK[i]);
        }
        return new DailyIndicators(macdHistMap, rsiMap, stochMap);
    }

    private void writeCsv(List<TradeSetup> setups, String csvPath) throws IOException {
        String header = "datetime,symbol,pattern_type,confirmation_type," +
                "low1_price,low2_price,neckline,fib_retrace_pct,pattern_height," +
                "reversal_pattern,rev_candle_open,rev_candle_high,rev_candle_low,rev_candle_close," +
                "sig_candle_open,sig_candle_high,sig_candle_low,sig_candle_close,sig_candle_volume," +
                "entry_price,stop_loss,target,risk_pct,reward_pct,rr_ratio," +
                "macd_histogram,macd_signal,stoch_rsi_k,stoch_rsi_d,bb_width,bb_contracting," +
                "daily_macd_histogram,daily_rsi,daily_stoch_rsi_k," +
                "result,bars_to_result,pnl_pct,exit_price,exit_reason,rsi_at_p1,rsi_at_p2,macd_hist_at_p1,macd_hist_at_p2\n";
        try (FileWriter fw = new FileWriter(csvPath)) {
            fw.write(header);
            for (TradeSetup s : setups) { fw.write(s.toCsvRow()); fw.write("\n"); }
        }
        log.info("[Triangle] CSV: {} rows → {}", setups.size(), csvPath);
    }

    private record DailyIndicators(
            TreeMap<Instant, Double> macdHistMap,
            TreeMap<Instant, Double> rsiMap,
            TreeMap<Instant, Double> stochRsiKMap) {
        double macdHistAtTs(Instant ts)  { return floorVal(macdHistMap, ts); }
        double rsiAtTs(Instant ts)       { return floorVal(rsiMap, ts); }
        double stochRsiKAtTs(Instant ts) { return floorVal(stochRsiKMap, ts); }
        private double floorVal(TreeMap<Instant, Double> m, Instant ts) {
            if (m.isEmpty()) return 0.0;
            Map.Entry<Instant, Double> e = m.floorEntry(ts);
            return e != null ? e.getValue() : 0.0;
        }
    }
}
