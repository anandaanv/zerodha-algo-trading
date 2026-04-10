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

@Service
@RequiredArgsConstructor
@Slf4j
public class DoubleTopBottomBacktestService {

    private static final int MACD_SHORT = 12;
    private static final int MACD_LONG = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;
    private static final int RSI_PERIOD = 14;
    private static final int BB_PERIOD = 20;
    private static final int ATR_PERIOD = 14;
    private static final double TRADE_OVERHEAD_PCT = 0.1;

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final CandlestickPatternDetector candlestickPatternDetector = new CandlestickPatternDetector();

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry point
    // ─────────────────────────────────────────────────────────────────────────

    public void runAndWriteCsv(String symbol, String csvPath) throws IOException {
        List<TradeSetup> setups = runForSymbol(symbol);
        writeCsv(setups, csvPath);
    }

    public void runMultipleAndWriteCsv(List<String> symbols, String csvPath) throws IOException {
        List<TradeSetup> allSetups = new ArrayList<>();
        int failed = 0;
        for (String symbol : symbols) {
            try {
                allSetups.addAll(runForSymbol(symbol));
            } catch (Exception e) {
                log.warn("[Backtest] Skipping {} due to error: {}", symbol, e.getMessage());
                failed++;
            }
        }
        log.info("[Backtest] Total setups across {} symbols: {} ({} failed)", symbols.size(), allSetups.size(), failed);
        writeCsv(allSetups, csvPath);
    }

    private List<TradeSetup> runForSymbol(String symbol) {
        log.info("[Backtest] Starting Double Top/Bottom backtest for {}", symbol);

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            log.error("[Backtest] Instrument not found: {}", symbol);
            return new ArrayList<>();
        }

        // Load bar series
        BarSeries hourlySeries = zigZagService.getBarSeries(symbol, instrument, Interval.OneHour);
        BarSeries dailySeries  = zigZagService.getBarSeries(symbol, instrument, Interval.Day);

        if (hourlySeries == null || hourlySeries.isEmpty()) {
            log.warn("[Backtest] No hourly data for {}", symbol);
            return new ArrayList<>();
        }

        log.info("[Backtest] Loaded {} hourly bars, {} daily bars",
                hourlySeries.getBarCount(), dailySeries != null ? dailySeries.getBarCount() : 0);

        // Compute ZigZag pivots on full history (BACKTEST mode)
        ZigZagParams params = zigZagService.resolveParams(symbol, Interval.OneHour);
        List<ZigZagPoint> pivots = zigZagService.detect(hourlySeries, params);

        log.info("[Backtest] Detected {} ZigZag pivots", pivots.size());

        // Convert BarSeries bars to a List<Bar> for random-access by timestamp
        List<Bar> hourlyBars = new ArrayList<>();
        for (int i = 0; i < hourlySeries.getBarCount(); i++) {
            hourlyBars.add(hourlySeries.getBar(i));
        }

        // Build timestamp → bar index map for hourly
        Map<Instant, Integer> hourlyTsToIdx = new HashMap<>();
        for (int i = 0; i < hourlyBars.size(); i++) {
            hourlyTsToIdx.put(hourlyBars.get(i).getEndTime(), i);
        }

        // Pre-compute indicators on hourly series
        ClosePriceIndicator close = new ClosePriceIndicator(hourlySeries);
        MACDIndicator macd = new MACDIndicator(close, MACD_SHORT, MACD_LONG);
        EMAIndicator macdSignalLine = new EMAIndicator(macd, MACD_SIGNAL_PERIOD);
        RSIIndicator rsi = new RSIIndicator(close, RSI_PERIOD);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(new EMAIndicator(close, BB_PERIOD));
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(close, BB_PERIOD);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev);
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev);

        // Stoch-RSI arrays
        int n = hourlySeries.getBarCount();
        double[] rsiValues = new double[n];
        for (int i = 0; i < n; i++) rsiValues[i] = safeDouble(rsi.getValue(i));
        double[] stochRsiK = computeStochRsiK(rsiValues);
        double[] stochRsiD = computeStochRsiD(stochRsiK);

        // ATR array
        double[] atrArr = computeAtr(hourlyBars, ATR_PERIOD);

        // BB widths array
        double[] bbWidths = new double[n];
        for (int i = 0; i < n; i++) {
            double mid = safeDouble(bbMiddle.getValue(i));
            double up  = safeDouble(bbUpper.getValue(i));
            double lo  = safeDouble(bbLower.getValue(i));
            bbWidths[i] = mid > 0 ? (up - lo) / mid : 0;
        }

        // Compute MACD histogram array
        double[] macdHistArr = new double[n];
        double[] macdSignalArr = new double[n];
        for (int i = 0; i < n; i++) {
            double macdVal = safeDouble(macd.getValue(i));
            double signalVal = safeDouble(macdSignalLine.getValue(i));
            macdHistArr[i] = macdVal - signalVal;
            macdSignalArr[i] = signalVal;
        }

        // Pre-compute daily indicators for parent TF lookup
        DailyIndicators dailyIndicators = computeDailyIndicators(dailySeries);

        // ── Scan for Double Bottom / Double Top patterns ───────────────────────
        List<TradeSetup> tradeSetups = new ArrayList<>();
        for (int i = 2; i < pivots.size(); i++) {
            ZigZagPoint p0 = pivots.get(i - 2);
            ZigZagPoint p1 = pivots.get(i - 1);
            ZigZagPoint p2 = pivots.get(i);
            // Prior same-direction pivot (i-4): LOW for double bottom, HIGH for double top
            ZigZagPoint priorPivot = i >= 4 ? pivots.get(i - 4) : null;

            // Double Bottom: LOW, HIGH (neckline), LOW
            if (p0.isLow() && p1.isHigh() && p2.isLow()) {
                detectDoubleBottom(p0, p1, p2, priorPivot, symbol, hourlyBars, hourlyTsToIdx,
                        macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                        rsiValues, dailyIndicators, macdHistArr, macdSignalArr, tradeSetups);
            }

            // Double Top: HIGH, LOW (neckline), HIGH
            if (p0.isHigh() && p1.isLow() && p2.isHigh()) {
                detectDoubleTop(p0, p1, p2, priorPivot, symbol, hourlyBars, hourlyTsToIdx,
                        macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                        rsiValues, dailyIndicators, macdHistArr, macdSignalArr, tradeSetups);
            }
        }

        log.info("[Backtest] Found {} trade setups", tradeSetups.size());
        return tradeSetups;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Double Bottom Detection
    // ─────────────────────────────────────────────────────────────────────────

    private void detectDoubleBottom(
            ZigZagPoint low1, ZigZagPoint necklinePoint, ZigZagPoint low2,
            ZigZagPoint priorLow,
            String symbol, List<Bar> bars, Map<Instant, Integer> tsToIdx,
            MACDIndicator macd, EMAIndicator macdSignalLine,
            double[] stochRsiK, double[] stochRsiD,
            double[] bbWidths, double[] atrArr,
            double[] rsiValues,
            DailyIndicators dailyIndicators, double[] macdHistArr, double[] macdSignalArr, List<TradeSetup> results) {

        double low1Price    = low1.getValue();
        double neckline     = necklinePoint.getValue();
        double low2Price    = low2.getValue();

        // Fib check: low2 must be 62%–100.5% retrace of swing (neckline - low1)
        double swing = neckline - low1Price;
        if (swing <= 0) return;
        double low2Retrace = (neckline - low2Price) / swing;
        if (low2Retrace < 0.62 || low2Retrace > 1.005) return;

        // Equal level check: Low1 and Low2 within 4%
        if (Math.abs(low1Price - low2Price) / low1Price > 0.04) return;

        Integer low2BarIdx = tsToIdx.get(low2.getTimestamp());
        if (low2BarIdx == null) return;

        // RSI divergence vs prior swing low (L0): record MACD divergence as data but don't filter on it
        if (priorLow == null) return; // need prior pivot for divergence check
        Integer priorLowBarIdx = tsToIdx.get(priorLow.getTimestamp());
        if (priorLowBarIdx == null) return;
        double rsiAtPriorLow = rsiValues[priorLowBarIdx];
        double rsiAtLow2 = rsiValues[low2BarIdx];
        // Bullish RSI divergence: RSI at L2 higher than RSI at prior low despite lower/similar price
        if (rsiAtLow2 <= rsiAtPriorLow) return;
        // Record MACD histogram at both pivots for analysis (not a hard filter)
        double macdHistAtPriorLow = priorLowBarIdx < macdHistArr.length ? macdHistArr[priorLowBarIdx] : 0;
        double macdHistAtLow2 = low2BarIdx < macdHistArr.length ? macdHistArr[low2BarIdx] : 0;

        double patternHeight = neckline - Math.min(low1Price, low2Price);
        double target = neckline + patternHeight;

        // ── C1: reversal candle at Low2, then breakout ─────────────────────
        int scanStart = Math.max(0, low2BarIdx - 3);
        int scanEnd   = Math.min(bars.size() - 1, low2BarIdx + 3);
        for (int i = scanStart; i <= scanEnd; i++) {
            CandlestickPatternDetector.PatternResult rev = candlestickPatternDetector.detectBullish(bars, i);
            if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

            double breakoutLevel = rev.breakoutLevel();
            // Scan forward for breakout
            for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                Bar bar = bars.get(j);
                if (bar.getHighPrice().doubleValue() > breakoutLevel) {
                    // Entry = open of bar j
                    double entryPrice = bar.getOpenPrice().doubleValue();
                    double stopLoss   = Math.min(low1Price, low2Price);
                    buildAndAddSetup("DOUBLE_BOTTOM", "C1", symbol, low1Price, low2Price, neckline,
                            low2Retrace, patternHeight, bars, i, j - 1, j, entryPrice, stopLoss, target,
                            macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                            dailyIndicators, rev.pattern(), macdHistArr, macdSignalArr, rsiAtPriorLow, rsiAtLow2, macdHistAtPriorLow, macdHistAtLow2, results);
                    break;
                }
            }
            break; // only first reversal candle found
        }

        // ── C3: neckline break → retest → reversal candle → breakout ──────
        Integer necklineBarIdx = tsToIdx.get(necklinePoint.getTimestamp());
        if (necklineBarIdx == null) return;

        // Scan forward from low2 for neckline break
        int searchStart = low2BarIdx + 1;
        int neckBreakIdx = -1;
        for (int i = searchStart; i < bars.size(); i++) {
            if (bars.get(i).getClosePrice().doubleValue() > neckline) {
                neckBreakIdx = i;
                break;
            }
        }
        if (neckBreakIdx < 0) return;

        // Look for retest within 1 ATR of neckline
        double atrAtBreak = neckBreakIdx < atrArr.length ? atrArr[neckBreakIdx] : 0;
        for (int i = neckBreakIdx + 1; i < Math.min(bars.size(), neckBreakIdx + 40); i++) {
            Bar b = bars.get(i);
            double low = b.getLowPrice().doubleValue();
            double high = b.getHighPrice().doubleValue();
            // Retest: bar touches within 1 ATR of neckline
            if (low <= neckline + atrAtBreak && low >= neckline - atrAtBreak) {
                // Look for reversal candle
                CandlestickPatternDetector.PatternResult rev = candlestickPatternDetector.detectBullish(bars, i);
                if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

                double breakoutLevel = rev.breakoutLevel();
                // Scan forward for breakout
                for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                    Bar bj = bars.get(j);
                    if (bj.getHighPrice().doubleValue() > breakoutLevel) {
                        double entryPrice = bj.getOpenPrice().doubleValue();
                        double stopLoss   = Math.min(low1Price, low2Price);
                        buildAndAddSetup("DOUBLE_BOTTOM", "C3", symbol, low1Price, low2Price, neckline,
                                low2Retrace, patternHeight, bars, i, j - 1, j, entryPrice, stopLoss, target,
                                macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                                dailyIndicators, rev.pattern(), macdHistArr, macdSignalArr, rsiAtPriorLow, rsiAtLow2, macdHistAtPriorLow, macdHistAtLow2, results);
                        break;
                    }
                }
                break; // only first retest
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Double Top Detection
    // ─────────────────────────────────────────────────────────────────────────

    private void detectDoubleTop(
            ZigZagPoint high1, ZigZagPoint necklinePoint, ZigZagPoint high2,
            ZigZagPoint priorHigh,
            String symbol, List<Bar> bars, Map<Instant, Integer> tsToIdx,
            MACDIndicator macd, EMAIndicator macdSignalLine,
            double[] stochRsiK, double[] stochRsiD,
            double[] bbWidths, double[] atrArr,
            double[] rsiValues,
            DailyIndicators dailyIndicators, double[] macdHistArr, double[] macdSignalArr, List<TradeSetup> results) {

        double high1Price   = high1.getValue();
        double neckline     = necklinePoint.getValue();
        double high2Price   = high2.getValue();

        // Fib check: high2 must be 62%–100.5% retrace of swing (high1 - neckline)
        double swing = high1Price - neckline;
        if (swing <= 0) return;
        double high2Retrace = (high2Price - neckline) / swing;
        if (high2Retrace < 0.62 || high2Retrace > 1.005) return;

        // Equal level check: High1 and High2 within 4%
        if (Math.abs(high1Price - high2Price) / high1Price > 0.04) return;

        Integer high2BarIdx = tsToIdx.get(high2.getTimestamp());
        if (high2BarIdx == null) return;

        // RSI divergence vs prior swing high (H0): record MACD divergence as data but don't filter on it
        if (priorHigh == null) return;
        Integer priorHighBarIdx = tsToIdx.get(priorHigh.getTimestamp());
        if (priorHighBarIdx == null) return;
        double rsiAtPriorHigh = rsiValues[priorHighBarIdx];
        double rsiAtHigh2 = rsiValues[high2BarIdx];
        // Bearish RSI divergence: RSI at H2 lower than RSI at prior high despite higher/similar price
        if (rsiAtHigh2 >= rsiAtPriorHigh) return;
        // Record MACD histogram at both pivots for analysis (not a hard filter)
        double macdHistAtPriorHigh = priorHighBarIdx < macdHistArr.length ? macdHistArr[priorHighBarIdx] : 0;
        double macdHistAtHigh2 = high2BarIdx < macdHistArr.length ? macdHistArr[high2BarIdx] : 0;

        double patternHeight = Math.max(high1Price, high2Price) - neckline;
        double target = neckline - patternHeight;

        // ── C1: reversal candle at High2, then breakdown ───────────────────
        int scanStart = Math.max(0, high2BarIdx - 3);
        int scanEnd   = Math.min(bars.size() - 1, high2BarIdx + 3);
        for (int i = scanStart; i <= scanEnd; i++) {
            CandlestickPatternDetector.PatternResult rev = candlestickPatternDetector.detectBearish(bars, i);
            if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

            double breakoutLevel = rev.breakoutLevel();
            for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                Bar bar = bars.get(j);
                if (bar.getLowPrice().doubleValue() < breakoutLevel) {
                    double entryPrice = bar.getOpenPrice().doubleValue();
                    double stopLoss   = Math.max(high1Price, high2Price);
                    buildAndAddSetup("DOUBLE_TOP", "C1", symbol, high1Price, high2Price, neckline,
                            high2Retrace, patternHeight, bars, i, j - 1, j, entryPrice, stopLoss, target,
                            macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                            dailyIndicators, rev.pattern(), macdHistArr, macdSignalArr, rsiAtPriorHigh, rsiAtHigh2, macdHistAtPriorHigh, macdHistAtHigh2, results);
                    break;
                }
            }
            break;
        }

        // ── C3: neckline break → retest → reversal candle → breakdown ─────
        int searchStart = high2BarIdx + 1;
        int neckBreakIdx = -1;
        for (int i = searchStart; i < bars.size(); i++) {
            if (bars.get(i).getClosePrice().doubleValue() < neckline) {
                neckBreakIdx = i;
                break;
            }
        }
        if (neckBreakIdx < 0) return;

        double atrAtBreak = neckBreakIdx < atrArr.length ? atrArr[neckBreakIdx] : 0;
        for (int i = neckBreakIdx + 1; i < Math.min(bars.size(), neckBreakIdx + 40); i++) {
            Bar b = bars.get(i);
            double highPrice = b.getHighPrice().doubleValue();
            if (highPrice >= neckline - atrAtBreak && highPrice <= neckline + atrAtBreak) {
                CandlestickPatternDetector.PatternResult rev = candlestickPatternDetector.detectBearish(bars, i);
                if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

                double breakoutLevel = rev.breakoutLevel();
                for (int j = i + 1; j < Math.min(bars.size(), i + 20); j++) {
                    Bar bj = bars.get(j);
                    if (bj.getLowPrice().doubleValue() < breakoutLevel) {
                        double entryPrice = bj.getOpenPrice().doubleValue();
                        double stopLoss   = Math.max(high1Price, high2Price);
                        buildAndAddSetup("DOUBLE_TOP", "C3", symbol, high1Price, high2Price, neckline,
                                high2Retrace, patternHeight, bars, i, j - 1, j, entryPrice, stopLoss, target,
                                macd, macdSignalLine, stochRsiK, stochRsiD, bbWidths, atrArr,
                                dailyIndicators, rev.pattern(), macdHistArr, macdSignalArr, rsiAtPriorHigh, rsiAtHigh2, macdHistAtPriorHigh, macdHistAtHigh2, results);
                        break;
                    }
                }
                break;
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Build a TradeSetup record and add to results
    // ─────────────────────────────────────────────────────────────────────────

    private void buildAndAddSetup(
            String patternType, String confirmationType,
            String symbol, double level1Price, double level2Price, double neckline,
            double fibRetrace, double patternHeight,
            List<Bar> bars, int revCandleIdx, int sigCandleIdx, int entryBarIdx,
            double entryPrice, double stopLoss, double target,
            MACDIndicator macd, EMAIndicator macdSignalLine,
            double[] stochRsiK, double[] stochRsiD,
            double[] bbWidths, double[] atrArr,
            DailyIndicators dailyIndicators,
            CandlestickPatternDetector.CandlePattern reversalPattern,
            double[] macdHistArr, double[] macdSignalArr,
            double rsiAtP1, double rsiAtP2,
            double macdHistAtP1, double macdHistAtP2,
            List<TradeSetup> results) {

        if (entryBarIdx >= bars.size()) return;
        if (stopLoss <= 0 || entryPrice <= 0) return;

        Bar revCandle = bars.get(revCandleIdx);
        Bar sigCandle = bars.get(sigCandleIdx);
        Bar entryBar  = bars.get(entryBarIdx);

        double riskPct   = Math.abs(entryPrice - stopLoss) / entryPrice * 100.0;
        double rewardPct = Math.abs(target - entryPrice) / entryPrice * 100.0;
        double rrRatio   = riskPct > 0 ? rewardPct / riskPct : 0;

        // BB contracting check: compare current width vs avg of last 5
        double bbWidth = sigCandleIdx < bbWidths.length ? bbWidths[sigCandleIdx] : 0;
        double bbAvg5  = avgBbWidth(bbWidths, sigCandleIdx, 5);
        boolean bbContracting = bbWidth < bbAvg5;

        // MACD at entry
        double macdVal    = sigCandleIdx < bars.size() ? safeDouble(macd.getValue(sigCandleIdx)) : 0;
        double macdSig    = sigCandleIdx < bars.size() ? safeDouble(macdSignalLine.getValue(sigCandleIdx)) : 0;
        double macdHist   = macdVal - macdSig;
        double stochKVal  = sigCandleIdx < stochRsiK.length ? stochRsiK[sigCandleIdx] : 0;
        double stochDVal  = sigCandleIdx < stochRsiD.length ? stochRsiD[sigCandleIdx] : 0;

        // Daily indicators at entry timestamp
        Instant entryTs = entryBar.getEndTime();
        double dailyMacdHist  = dailyIndicators.macdHistAtTs(entryTs);
        double dailyRsiVal    = dailyIndicators.rsiAtTs(entryTs);
        double dailyStochRsiK = dailyIndicators.stochRsiKAtTs(entryTs);

        // Determine result by scanning forward
        String result = "OPEN";
        int barsToResult = 0;
        double pnlPct = 0.0;
        double exitPrice = 0.0;
        String exitReason = "OPEN";
        boolean bullish = "DOUBLE_BOTTOM".equals(patternType);

        // Start from entryBarIdx + 1 and check SL/exit triggers
        for (int k = entryBarIdx + 1; k < bars.size(); k++) {
            Bar b = bars.get(k);

            // --- SL check (highest priority) ---
            if (bullish && b.getLowPrice().doubleValue() <= stopLoss) {
                result = "STOP_HIT";
                exitReason = "STOP_HIT";
                exitPrice = (k + 1 < bars.size()) ? bars.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                pnlPct = (exitPrice - entryPrice) / entryPrice * 100.0 - TRADE_OVERHEAD_PCT;
                barsToResult = k - entryBarIdx;
                break;
            }
            if (!bullish && b.getHighPrice().doubleValue() >= stopLoss) {
                result = "STOP_HIT";
                exitReason = "STOP_HIT";
                exitPrice = (k + 1 < bars.size()) ? bars.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                pnlPct = (entryPrice - exitPrice) / entryPrice * 100.0 - TRADE_OVERHEAD_PCT;
                barsToResult = k - entryBarIdx;
                break;
            }

            // --- Exit triggers (only after 3 bars) ---
            if (k >= entryBarIdx + 3) {
                boolean exitTriggered = false;

                // Trigger A: Candlestick reversal pattern
                if (bullish) {
                    CandlestickPatternDetector.PatternResult bearishCandle = candlestickPatternDetector.detectBearish(bars, k);
                    if (bearishCandle.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                        exitTriggered = true;
                        exitReason = "BEARISH_CANDLE";
                    }
                } else {
                    CandlestickPatternDetector.PatternResult bullishCandle = candlestickPatternDetector.detectBullish(bars, k);
                    if (bullishCandle.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                        exitTriggered = true;
                        exitReason = "BULLISH_CANDLE";
                    }
                }

                // Trigger B1: MACD histogram sign flip
                if (!exitTriggered && k > 0 && k < macdHistArr.length) {
                    if (bullish && macdHistArr[k-1] > 0 && macdHistArr[k] <= 0) {
                        exitTriggered = true;
                        exitReason = "MACD_HIST_CROSS";
                    } else if (!bullish && macdHistArr[k-1] < 0 && macdHistArr[k] >= 0) {
                        exitTriggered = true;
                        exitReason = "MACD_HIST_CROSS";
                    }
                }

                // Trigger B2: MACD signal line crossover
                if (!exitTriggered && k > 0 && k < macdHistArr.length && k < macdSignalArr.length) {
                    double macdLinePrev = macdHistArr[k-1] + macdSignalArr[k-1];
                    double macdLineCurr = macdHistArr[k] + macdSignalArr[k];
                    double signalPrev = macdSignalArr[k-1];
                    double signalCurr = macdSignalArr[k];
                    if (bullish && macdLinePrev >= signalPrev && macdLineCurr < signalCurr) {
                        exitTriggered = true;
                        exitReason = "MACD_SIGNAL_CROSS";
                    } else if (!bullish && macdLinePrev <= signalPrev && macdLineCurr > signalCurr) {
                        exitTriggered = true;
                        exitReason = "MACD_SIGNAL_CROSS";
                    }
                }

                if (exitTriggered) {
                    exitPrice = (k + 1 < bars.size()) ? bars.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                    if (bullish) {
                        pnlPct = (exitPrice - entryPrice) / entryPrice * 100.0 - TRADE_OVERHEAD_PCT;
                    } else {
                        pnlPct = (entryPrice - exitPrice) / entryPrice * 100.0 - TRADE_OVERHEAD_PCT;
                    }
                    result = pnlPct > 0 ? "WIN" : "LOSS";
                    barsToResult = k - entryBarIdx;
                    break;
                }
            }
        }

        results.add(TradeSetup.builder()
                .datetime(entryTs.toString())
                .symbol(symbol)
                .patternType(patternType)
                .confirmationType(confirmationType)
                .level1Price(level1Price)
                .level2Price(level2Price)
                .neckline(neckline)
                .fibRetracePct(fibRetrace * 100.0)
                .patternHeight(patternHeight)
                .reversalPattern(reversalPattern.name())
                .revCandleOpen(revCandle.getOpenPrice().doubleValue())
                .revCandleHigh(revCandle.getHighPrice().doubleValue())
                .revCandleLow(revCandle.getLowPrice().doubleValue())
                .revCandleClose(revCandle.getClosePrice().doubleValue())
                .sigCandleOpen(sigCandle.getOpenPrice().doubleValue())
                .sigCandleHigh(sigCandle.getHighPrice().doubleValue())
                .sigCandleLow(sigCandle.getLowPrice().doubleValue())
                .sigCandleClose(sigCandle.getClosePrice().doubleValue())
                .sigCandleVolume(sigCandle.getVolume().doubleValue())
                .entryPrice(entryPrice)
                .stopLoss(stopLoss)
                .target(target)
                .riskPct(riskPct)
                .rewardPct(rewardPct)
                .rrRatio(rrRatio)
                .macdHistogram(macdHist)
                .macdSignal(macdSig)
                .stochRsiK(stochKVal)
                .stochRsiD(stochDVal)
                .bbWidth(bbWidth)
                .bbContracting(bbContracting ? "CONTRACTING" : "EXPANDING")
                .dailyMacdHistogram(dailyMacdHist)
                .dailyRsi(dailyRsiVal)
                .dailyStochRsiK(dailyStochRsiK)
                .rsiAtP1(rsiAtP1)
                .rsiAtP2(rsiAtP2)
                .macdHistAtP1(macdHistAtP1)
                .macdHistAtP2(macdHistAtP2)
                .result(result)
                .barsToResult(barsToResult)
                .pnlPct(pnlPct)
                .exitPrice(exitPrice)
                .exitReason(exitReason)
                .build());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CSV writer
    // ─────────────────────────────────────────────────────────────────────────

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
            for (TradeSetup s : setups) {
                fw.write(s.toCsvRow());
                fw.write("\n");
            }
        }
        log.info("[Backtest] CSV written: {} rows to {}", setups.size(), csvPath);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Indicator helpers
    // ─────────────────────────────────────────────────────────────────────────

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

    private double[] computeAtr(List<Bar> bars, int period) {
        int n = bars.size();
        double[] tr = new double[n];
        double[] atr = new double[n];
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            double high = b.getHighPrice().doubleValue();
            double low  = b.getLowPrice().doubleValue();
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

    private double avgBbWidth(double[] bbWidths, int idx, int lookback) {
        if (idx < lookback) return bbWidths[idx];
        double sum = 0;
        for (int i = idx - lookback; i < idx; i++) sum += bbWidths[i];
        return sum / lookback;
    }

    private double safeDouble(org.ta4j.core.num.Num num) {
        try {
            return num == null || num.isNaN() ? 0.0 : num.doubleValue();
        } catch (Exception e) {
            return 0.0;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Daily indicator computation
    // ─────────────────────────────────────────────────────────────────────────

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

        TreeMap<Instant, Double> macdHistMap  = new TreeMap<>();
        TreeMap<Instant, Double> rsiMap       = new TreeMap<>();
        TreeMap<Instant, Double> stochRsiKMap = new TreeMap<>();

        for (int i = 0; i < m; i++) {
            Instant ts = dailySeries.getBar(i).getEndTime();
            double macdVal  = safeDouble(dMacd.getValue(i));
            double macdSig  = safeDouble(dMacdSignal.getValue(i));
            macdHistMap.put(ts, macdVal - macdSig);
            rsiMap.put(ts, dRsiValues[i]);
            stochRsiKMap.put(ts, dStochK[i]);
        }
        return new DailyIndicators(macdHistMap, rsiMap, stochRsiKMap);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Inner classes
    // ─────────────────────────────────────────────────────────────────────────

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
