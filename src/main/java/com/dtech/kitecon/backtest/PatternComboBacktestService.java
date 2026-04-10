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
    private static final double KEY_LEVEL_PROXIMITY_ATR = 1.5;
    private static final double MIN_COMBO_RR = 1.5;

    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final CandlestickPatternDetector candlestickPatternDetector = new CandlestickPatternDetector();

    // ─────────────────────────────────────────────────────────────────────────
    // Public entry points
    // ─────────────────────────────────────────────────────────────────────────

    public void runAndWriteCsv(String symbol, String csvPath) throws IOException {
        List<ComboRow> combos = runForSymbol(symbol, Interval.OneHour, Interval.FifteenMinute);
        writeCsv(combos, csvPath);
    }

    public void runAndWriteCsv(String symbol, String csvPath, Interval watchingTf, Interval confirmTf) throws IOException {
        List<ComboRow> combos = runForSymbol(symbol, watchingTf, confirmTf);
        writeCsv(combos, csvPath);
    }

    public void runMultipleAndWriteCsv(List<String> symbols, String csvPath) throws IOException {
        runMultipleAndWriteCsv(symbols, csvPath, Interval.OneHour, Interval.FifteenMinute);
    }

    public void runMultipleAndWriteCsv(List<String> symbols, String csvPath, Interval watchingTf, Interval confirmTf) throws IOException {
        List<ComboRow> allCombos = new ArrayList<>();
        int failed = 0;
        for (String symbol : symbols) {
            try {
                allCombos.addAll(runForSymbol(symbol, watchingTf, confirmTf));
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

    private List<ComboRow> runForSymbol(String symbol, Interval watchingTf, Interval confirmTf) {
        log.info("[Combo] Starting multi-TF combo backtest for {} ({}+{})", symbol, watchingTf.getUiKey(), confirmTf.getUiKey());

        // Bar count constants scaled to the confirmation TF (baseline: 80 and 400 × 15m bars)
        int confirmWindowBars      = (int)(80L  * 900 / confirmTf.getOffset());
        int maxHoldBars            = (int)(400L * 900 / confirmTf.getOffset());
        int minBarsBeforeReversal  = Math.max(3, (int)(6.0 * 900 / confirmTf.getOffset()));

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            log.error("[Combo] Instrument not found: {}", symbol);
            return new ArrayList<>();
        }

        // Load watching TF, confirmation TF, and daily series
        BarSeries seriesW = zigZagService.getBarSeries(symbol, instrument, watchingTf);
        BarSeries seriesC = zigZagService.getBarSeries(symbol, instrument, confirmTf);
        BarSeries seriesDaily = zigZagService.getBarSeries(symbol, instrument, Interval.Day);

        if (seriesW == null || seriesW.isEmpty()) {
            log.warn("[Combo] No {} watching data for {}", watchingTf.getUiKey(), symbol);
            return new ArrayList<>();
        }

        List<Bar> barsW = toList(seriesW);
        Map<Instant, Integer> tsToIdxW = buildTsToIdx(barsW);

        // Compute watching TF indicators
        double[] atrArrW = computeAtr(barsW, ATR_PERIOD);
        double[] rsiValuesW = new double[seriesW.getBarCount()];
        RSIIndicator rsiW = new RSIIndicator(new ClosePriceIndicator(seriesW), RSI_PERIOD);
        for (int i = 0; i < seriesW.getBarCount(); i++) {
            rsiValuesW[i] = safeDouble(rsiW.getValue(i));
        }
        double[] stochRsiKW = computeStochRsiK(rsiValuesW);

        // Compute watching TF MACD histogram
        ClosePriceIndicator closeW = new ClosePriceIndicator(seriesW);
        MACDIndicator macdW = new MACDIndicator(closeW, MACD_SHORT, MACD_LONG);
        EMAIndicator macdSignalW = new EMAIndicator(macdW, MACD_SIGNAL_PERIOD);
        double[] macdHistArrW = new double[seriesW.getBarCount()];
        for (int i = 0; i < seriesW.getBarCount(); i++) {
            double macdVal = safeDouble(macdW.getValue(i));
            double signalVal = safeDouble(macdSignalW.getValue(i));
            macdHistArrW[i] = macdVal - signalVal;
        }

        // Get watching TF ZigZag pivots
        ZigZagParams paramsW = zigZagService.resolveParams(symbol, watchingTf);
        List<ZigZagPoint> pivotsW = zigZagService.detect(seriesW, paramsW);
        log.info("[Combo] {}: {} pivots", watchingTf.getUiKey(), pivotsW.size());

        // Detect watching patterns on watching TF
        List<DetectedPattern> watchingPatterns = new ArrayList<>();
        watchingPatterns.addAll(scanDtbWatching(pivotsW, barsW, tsToIdxW, atrArrW, rsiValuesW, macdHistArrW, stochRsiKW));
        watchingPatterns.addAll(scanTriangleWatching(pivotsW, barsW, tsToIdxW, atrArrW, rsiValuesW, macdHistArrW, stochRsiKW));
        watchingPatterns.addAll(scanHnsWatching(pivotsW, barsW, tsToIdxW, atrArrW, rsiValuesW, macdHistArrW, stochRsiKW));
        Map<String, Long> wpByType = watchingPatterns.stream()
                .collect(java.util.stream.Collectors.groupingBy(DetectedPattern::getPatternType, java.util.stream.Collectors.counting()));
        log.info("[Combo] Found {} watching patterns ({}): {}", watchingPatterns.size(), watchingTf.getUiKey(), wpByType);

        if (seriesC == null || seriesC.isEmpty()) {
            log.warn("[Combo] No {} confirmation data for {}", confirmTf.getUiKey(), symbol);
            return new ArrayList<>();
        }

        List<Bar> barsC = toList(seriesC);
        Map<Instant, Integer> tsToIdxC = buildTsToIdx(barsC);

        // Compute confirmation TF indicators
        double[] atrArrC = computeAtr(barsC, ATR_PERIOD);
        double[] rsiValuesC = new double[seriesC.getBarCount()];
        RSIIndicator rsiC = new RSIIndicator(new ClosePriceIndicator(seriesC), RSI_PERIOD);
        for (int i = 0; i < seriesC.getBarCount(); i++) {
            rsiValuesC[i] = safeDouble(rsiC.getValue(i));
        }
        double[] stochRsiKC = computeStochRsiK(rsiValuesC);

        // Compute confirmation TF MACD histogram
        ClosePriceIndicator closeC = new ClosePriceIndicator(seriesC);
        MACDIndicator macdC = new MACDIndicator(closeC, MACD_SHORT, MACD_LONG);
        EMAIndicator macdSignalC = new EMAIndicator(macdC, MACD_SIGNAL_PERIOD);
        double[] macdHistArrC = new double[seriesC.getBarCount()];
        for (int i = 0; i < seriesC.getBarCount(); i++) {
            double macdVal = safeDouble(macdC.getValue(i));
            double signalVal = safeDouble(macdSignalC.getValue(i));
            macdHistArrC[i] = macdVal - signalVal;
        }

        // Compute daily indicators for confirmation TF entry lookups
        DailyIndicators dailyInd = computeDailyIndicators(seriesDaily);

        // Compute Bollinger Bands on confirmation TF (20-period, 2σ) for exit filtering
        double[][] bbC = computeBollingerBands(barsC, 20, 2.0);
        double[] bbUpperC = bbC[0];
        double[] bbLowerC = bbC[2];

        // Get confirmation TF ZigZag pivots.
        // Disable dynamicPctEnabled to avoid volMult*rvol dominating (which makes fast TFs
        // behave like 1h regardless of atrMult). Use fixed pctMin=0.3% with tight ATR mult.
        ZigZagParams base = zigZagService.resolveParams(symbol, Interval.OneHour);
        int minPivotBars = confirmTf.getOffset() <= 300 ? 5 : 3;  // 5m: 5 bars, 15m: 3 bars
        ZigZagParams paramsC = ZigZagParams.builder()
                .atrLength(base.getAtrLength())
                .atrMult(0.5)
                .pctMin(0.003)
                .hysteresis(1.2)
                .minBarsBetweenPivots(minPivotBars)
                .dynamicPctEnabled(false)
                .volMult(base.getVolMult())
                .rvolWindow(base.getRvolWindow())
                .mode(ZigZagParams.Mode.BACKTEST)
                .build();
        List<ZigZagPoint> pivotsC = zigZagService.detect(seriesC, paramsC);
        log.info("[Combo] {}: {} pivots", confirmTf.getUiKey(), pivotsC.size());

        // Detect confirmation patterns on confirmation TF
        List<DetectedPattern> confirmPatterns = new ArrayList<>();
        confirmPatterns.addAll(scanDtbConfirmation(pivotsC, barsC, tsToIdxC, atrArrC, rsiValuesC, macdHistArrC, stochRsiKC, dailyInd));
        confirmPatterns.addAll(scanTriangleConfirmation(pivotsC, barsC, tsToIdxC, atrArrC, rsiValuesC, macdHistArrC, stochRsiKC, dailyInd));
        confirmPatterns.addAll(scanCandleClusterConfirmation(barsC, atrArrC, rsiValuesC, macdHistArrC, stochRsiKC, dailyInd));
        // Triangle confirmations: reversal candle on 15m near point E (apex price ± ATR)
        List<DetectedPattern> triangleWatching = new ArrayList<>();
        for (DetectedPattern wp : watchingPatterns) {
            if (wp.getPatternType().startsWith("TRIANGLE")) triangleWatching.add(wp);
        }
        confirmPatterns.addAll(scanTriangleCandleConfirmation(triangleWatching,
                barsC, atrArrC, rsiValuesC, stochRsiKC, dailyInd));
        // H&S confirmations: reversal candle on 15m near E (C1) or neckline retrace (C2)
        List<DetectedPattern> hnsWatching = new ArrayList<>();
        for (DetectedPattern wp : watchingPatterns) {
            if (wp.getPatternType().startsWith("HNS")) hnsWatching.add(wp);
        }
        confirmPatterns.addAll(scanHnsCandleConfirmation(hnsWatching,
                barsC, atrArrC, rsiValuesC, stochRsiKC, dailyInd));
        log.info("[Combo] Found {} confirmation patterns ({})", confirmPatterns.size(), confirmTf.getUiKey());

        // Cross-match watching × confirmation
        List<ComboRow> results = new ArrayList<>();
        Set<String> seenCombos = new HashSet<>();

        for (DetectedPattern wp : watchingPatterns) {
            for (DetectedPattern cp : confirmPatterns) {
                // Same direction check
                if (cp.bullish != wp.bullish) continue;

                // Triangles: entry is on MACD histogram trendline breakout only
                if (wp.getPatternType().startsWith("TRIANGLE") && !"TRIANGLE_CANDLE".equals(cp.getPatternType())) continue;

                // H&S: confirmation must be HNS_CANDLE type only
                if (wp.getPatternType().startsWith("HNS") && !"HNS_CANDLE".equals(cp.getPatternType())) continue;

                // Time window check (wall-clock: 20h)
                if (cp.getKeyLevelTime().isBefore(wp.getKeyLevelTime())) continue;
                if (cp.getKeyLevelTime().isAfter(wp.getKeyLevelTime().plusSeconds(20 * 3600L))) continue;

                // Price proximity check — triangles: trendline entry may be offset from apex
                double proximityMultiplier = wp.getPatternType().startsWith("TRIANGLE") ? 3.0 : KEY_LEVEL_PROXIMITY_ATR;
                if (Math.abs(cp.getEntryPrice() - wp.getKeyLevel()) > proximityMultiplier * wp.getAtr()) continue;

                // Compute combo parameters
                double comboEntry = cp.getEntryPrice();
                double comboSL = cp.getStopLoss();
                // For triangle watching patterns: target = 80% of the way from entry to previous extreme (h1/l1).
                // For DTB watching patterns: use the full measured-move target as-is.
                double comboTarget;
                if (wp.getPatternType().startsWith("TRIANGLE")) {
                    comboTarget = wp.isBullish()
                        ? comboEntry + 0.8 * (wp.getOwnTarget() - comboEntry)
                        : comboEntry - 0.8 * (comboEntry - wp.getOwnTarget());
                } else {
                    comboTarget = wp.getOwnTarget();
                }

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

                // Simulate exit on confirmation TF bars
                Integer entryBarIdx = tsToIdxC.get(cp.getKeyLevelTime());
                if (entryBarIdx == null) continue;

                String result = "OPEN";
                int barsToResult = 0;
                double pnlPct = 0.0;
                double exitPrice = 0.0;
                String exitReason = "OPEN";
                double peakPrice = comboEntry; // tracks best price seen since entry

                // Exit simulation — use price-direction bullish (set above, consistent with SL guard)
                for (int k = entryBarIdx + 1; k < Math.min(barsC.size(), entryBarIdx + maxHoldBars); k++) {
                    Bar b = barsC.get(k);

                    // SL check
                    if (bullish && b.getLowPrice().doubleValue() <= comboSL) {
                        result = "STOP_HIT";
                        exitReason = "STOP_HIT";
                        exitPrice = (k + 1 < barsC.size()) ? barsC.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                        pnlPct = (exitPrice - comboEntry) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                        barsToResult = k - entryBarIdx;
                        break;
                    }
                    if (!bullish && b.getHighPrice().doubleValue() >= comboSL) {
                        result = "STOP_HIT";
                        exitReason = "STOP_HIT";
                        exitPrice = (k + 1 < barsC.size()) ? barsC.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                        pnlPct = (comboEntry - exitPrice) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                        barsToResult = k - entryBarIdx;
                        break;
                    }

                    // Target check
                    if (bullish && b.getHighPrice().doubleValue() >= comboTarget) {
                        result = "WIN";
                        exitReason = "TARGET_HIT";
                        exitPrice = comboTarget;
                        pnlPct = (comboTarget - comboEntry) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                        barsToResult = k - entryBarIdx;
                        break;
                    }
                    if (!bullish && b.getLowPrice().doubleValue() <= comboTarget) {
                        result = "WIN";
                        exitReason = "TARGET_HIT";
                        exitPrice = comboTarget;
                        pnlPct = (comboEntry - comboTarget) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                        barsToResult = k - entryBarIdx;
                        break;
                    }

                    // Update peak price (best price reached so far in trade direction)
                    if (bullish) peakPrice = Math.max(peakPrice, b.getHighPrice().doubleValue());
                    else         peakPrice = Math.min(peakPrice, b.getLowPrice().doubleValue());

                    // 38% Fibonacci retrace exit (after minBarsBeforeReversal bars)
                    // Only trigger once price has moved at least 1 ATR in our direction (filters noise).
                    // If price then retraces more than 38.2% of that move → real reversal, exit.
                    // Less than 38% = just a pause, hold.
                    if (k >= entryBarIdx + minBarsBeforeReversal) {
                        double atrAtEntry = entryBarIdx < atrArrC.length ? atrArrC[entryBarIdx] : 0;
                        double moveSize = bullish ? (peakPrice - comboEntry) : (comboEntry - peakPrice);
                        if (atrAtEntry > 0 && moveSize >= atrAtEntry) {
                            // Exit when retrace exceeds 23% of move + 1 ATR buffer
                            double retrace23 = bullish
                                ? peakPrice - (0.236 * moveSize + atrAtEntry)   // bull: 23.6% + ATR back from peak
                                : peakPrice + (0.236 * moveSize + atrAtEntry);  // bear: 23.6% + ATR back up from trough
                            boolean retracedTooMuch = bullish
                                ? b.getLowPrice().doubleValue() < retrace23
                                : b.getHighPrice().doubleValue() > retrace23;
                            if (retracedTooMuch) {
                                exitReason = "RETRACE_23";
                                exitPrice = (k + 1 < barsC.size()) ? barsC.get(k + 1).getOpenPrice().doubleValue() : b.getClosePrice().doubleValue();
                                pnlPct = bullish
                                    ? (exitPrice - comboEntry) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT
                                    : (comboEntry - exitPrice) / comboEntry * 100.0 - TRADE_OVERHEAD_PCT;
                                result = pnlPct > 0 ? "WIN" : "LOSS";
                                barsToResult = k - entryBarIdx;
                                break;
                            }
                        }
                    }
                }

                // Check if target was eventually hit (even if we exited early)
                boolean targetEventuallyHit = result.equals("WIN") && "TARGET_HIT".equals(exitReason);
                if (!targetEventuallyHit) {
                    int scanFrom = (barsToResult > 0) ? entryBarIdx + barsToResult : entryBarIdx + 1;
                    for (int m = scanFrom; m < Math.min(barsC.size(), entryBarIdx + maxHoldBars); m++) {
                        Bar mb = barsC.get(m);
                        if (bullish && mb.getHighPrice().doubleValue() >= comboTarget) { targetEventuallyHit = true; break; }
                        if (!bullish && mb.getLowPrice().doubleValue() <= comboTarget) { targetEventuallyHit = true; break; }
                    }
                }

                // Track deepest retrace from peak after exit (for RETRACE_23 exits: how deep did price go?)
                double postExitMaxRetracePct = 0.0;
                if ("RETRACE_23".equals(exitReason) || "RETRACE_38".equals(exitReason)) {
                    double deepest = peakPrice;
                    int scanFrom2 = entryBarIdx + barsToResult + 1;
                    for (int m = scanFrom2; m < Math.min(barsC.size(), entryBarIdx + maxHoldBars); m++) {
                        Bar mb = barsC.get(m);
                        if (bullish) deepest = Math.min(deepest, mb.getLowPrice().doubleValue());
                        else         deepest = Math.max(deepest, mb.getHighPrice().doubleValue());
                    }
                    double fullMove = bullish ? (peakPrice - comboEntry) : (comboEntry - peakPrice);
                    if (fullMove > 0) {
                        double totalRetrace = bullish ? (peakPrice - deepest) : (deepest - peakPrice);
                        postExitMaxRetracePct = totalRetrace / fullMove * 100.0;
                    }
                }

                results.add(ComboRow.builder()
                        .symbol(symbol)
                        .watchingPattern(wp.getPatternType())
                        .watchingTf(watchingTf.getUiKey())
                        .confirmPattern(cp.getPatternType())
                        .confirmTf(confirmTf.getUiKey())
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
                        .targetEventuallyHit(targetEventuallyHit)
                        .postExitMaxRetracePct(postExitMaxRetracePct)
                        .eSymmetry(wp.getPatternType().startsWith("HNS") ? cp.getMacdHistAtP1() : 0.0)
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

        for (int i = 5; i < pivots.size(); i++) {
            ZigZagPoint prior = pivots.get(i - 5);
            ZigZagPoint p0 = pivots.get(i - 4);
            ZigZagPoint p1 = pivots.get(i - 3);
            ZigZagPoint p2 = pivots.get(i - 2);
            ZigZagPoint p3 = pivots.get(i - 1);
            ZigZagPoint p4 = pivots.get(i);

            // Both orientations
            checkTriangleWatching(prior, p0, p1, p2, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, results);
        }
        return results;
    }

    private void checkTriangleWatching(ZigZagPoint prior, ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2, ZigZagPoint p3, ZigZagPoint p4,
            List<Bar> bars, Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, List<DetectedPattern> results) {

        // High-first: H, L, H, L, H
        if (p0.isHigh() && p1.isLow() && p2.isHigh() && p3.isLow() && p4.isHigh()) {
            processTriangleWatching(prior, p0, p2, p1, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, results, true);
        }

        // Low-first: L, H, L, H, L
        if (p0.isLow() && p1.isHigh() && p2.isLow() && p3.isHigh() && p4.isLow()) {
            processTriangleWatching(prior, p1, p3, p0, p2, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, results, false);
        }
    }

    private void processTriangleWatching(ZigZagPoint priorPivot, ZigZagPoint pHigh1, ZigZagPoint pHigh2, ZigZagPoint pLow1, ZigZagPoint pLow2, ZigZagPoint pLast,
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
        else if (lowerRising  && (upperFlat || upperFalling))   { triangleType = "TRIANGLE_ASCENDING";   bullish = false; } // reversal → bearish
        else if (upperFalling && (lowerFlat || lowerFalling))   { triangleType = "TRIANGLE_DESCENDING";  bullish = true;  } // reversal → bullish
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

        // SYMMETRIC is a continuation pattern — prior trend determines breakout direction.
        // ASCENDING (bullish=true) and DESCENDING (bullish=false) directions are fixed by type.
        if ("TRIANGLE_SYMMETRIC".equals(triangleType)) {
            if (priorPivot == null) return;
            if (priorPivot.isLow() && priorPivot.getValue() < l1) {
                bullish = true;   // prior uptrend → continue up
            } else if (priorPivot.isHigh() && priorPivot.getValue() > h1) {
                bullish = false;  // prior downtrend → continue down
            } else {
                return; // no clear prior trend for symmetric
            }
        }

        // Compute projected trendlines at last pivot bar for target calculation
        double projectedUpper = h1 + upperSlope * (lastIdx - h1Idx);
        double projectedLower = l1 + lowerSlope * (lastIdx - l1Idx);

        // Target = previous extreme of the triangle (not measured move).
        // For DESCENDING (bullish): previous high = max(h1,h2)  → price should recover to h1
        // For ASCENDING  (bearish): previous low  = min(l1,l2)  → price should fall to l1
        // For SYMMETRIC: same — highest high for bull, lowest low for bear
        // Actual combo target will be 80% of the way from entry to this level.
        double target;
        if (bullish) {
            target = Math.max(h1, h2);
        } else {
            target = Math.min(l1, l2);
        }

        // Pivot B and D times for MACD histogram trendline (same-type pivots used for trendline)
        Instant pivotBTime, pivotDTime;
        if ("TRIANGLE_ASCENDING".equals(triangleType)) {
            // Bearish trade: trendline through histogram lows at the price lows (B=L1, D=L2)
            pivotBTime = pLow1.getTimestamp();
            pivotDTime = pLow2.getTimestamp();
        } else if ("TRIANGLE_DESCENDING".equals(triangleType)) {
            // Bullish trade: trendline through histogram highs at the price highs (B=H1, D=H2)
            pivotBTime = pHigh1.getTimestamp();
            pivotDTime = pHigh2.getTimestamp();
        } else { // SYMMETRIC: use lows for bullish, highs for bearish
            pivotBTime = bullish ? pLow1.getTimestamp()  : pHigh1.getTimestamp();
            pivotDTime = bullish ? pLow2.getTimestamp() : pHigh2.getTimestamp();
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
                .pivotBTime(pivotBTime)
                .pivotDTime(pivotDTime)
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

        for (int i = 5; i < pivots.size(); i++) {
            ZigZagPoint prior = pivots.get(i - 5);
            ZigZagPoint p0 = pivots.get(i - 4);
            ZigZagPoint p1 = pivots.get(i - 3);
            ZigZagPoint p2 = pivots.get(i - 2);
            ZigZagPoint p3 = pivots.get(i - 1);
            ZigZagPoint p4 = pivots.get(i);

            checkTriangleConfirmation(prior, p0, p1, p2, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, dailyInd, results);
        }
        return results;
    }

    private void checkTriangleConfirmation(ZigZagPoint prior, ZigZagPoint p0, ZigZagPoint p1, ZigZagPoint p2, ZigZagPoint p3, ZigZagPoint p4,
            List<Bar> bars, Map<Instant, Integer> tsToIdx, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, DailyIndicators dailyInd, List<DetectedPattern> results) {

        if (p0.isHigh() && p1.isLow() && p2.isHigh() && p3.isLow() && p4.isHigh()) {
            processTriangleConfirmation(prior, p0, p2, p1, p3, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, dailyInd, results, true);
        }

        if (p0.isLow() && p1.isHigh() && p2.isLow() && p3.isHigh() && p4.isLow()) {
            processTriangleConfirmation(prior, p1, p3, p0, p2, p4, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK, dailyInd, results, false);
        }
    }

    private void processTriangleConfirmation(ZigZagPoint priorPivot, ZigZagPoint pHigh1, ZigZagPoint pHigh2, ZigZagPoint pLow1, ZigZagPoint pLow2, ZigZagPoint pLast,
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

        // Classify and validate prior trend (same rules as watching scanner)
        boolean upperFalling = h2 < h1 - ATR_FLAT_FACTOR * atrAtLast;
        boolean upperRising  = h2 > h1 + ATR_FLAT_FACTOR * atrAtLast;
        boolean lowerRising  = l2 > l1 + ATR_FLAT_FACTOR * atrAtLast;
        boolean lowerFalling = l2 < l1 - ATR_FLAT_FACTOR * atrAtLast;
        boolean upperFlat    = !upperFalling && !upperRising;
        boolean lowerFlat    = !lowerRising  && !lowerFalling;

        boolean isSymmetric  = upperFalling && lowerRising;
        boolean isAscending  = lowerRising  && (upperFlat || upperFalling) && !isSymmetric;
        boolean isDescending = upperFalling && (lowerFlat || lowerFalling) && !isSymmetric;

        if (isSymmetric) {
            // Continuation: prior trend must be clear to determine direction
            if (priorPivot == null) return;
            boolean priorUp   = priorPivot.isLow()  && priorPivot.getValue() < l1;
            boolean priorDown = priorPivot.isHigh() && priorPivot.getValue() > h1;
            if (!priorUp && !priorDown) return;
        } else if (!isAscending && !isDescending) {
            return; // expanding or unclassified
        }

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

    /**
     * Detects MACD histogram trendline breakout for triangle watching patterns.
     *
     * For each triangle watching pattern (1h), the MACD histogram on the confirmation TF (15m)
     * forms a squeeze mirroring the price triangle. We draw a trendline through the histogram
     * values at the B and D pivots (same-type pivots), then detect when the histogram breaks
     * through that trendline — the actual momentum breakout entry.
     *
     * Ascending triangle (bearish): B and D are price lows → histogram is negative at those points.
     *   histB = min(hist ±2 bars around B), histD = min around D.
     *   Trendline slope = (histD - histB) / bars. Entry when hist < projected trendline.
     *
     * Descending triangle (bullish): B and D are price highs → histogram is positive.
     *   histB = max around B, histD = max around D.
     *   Entry when hist > projected trendline.
     *
     * Symmetric: same logic, using lows for bullish and highs for bearish.
     */
    /**
     * Detects MACD histogram trendline breakout on the WATCHING TF (1h), then
     * maps the signal to the CONFIRMATION TF (15m) for the actual entry price and SL.
     *
     * Trendline is drawn through histogram values at the B and D pivots on 1h.
     * When the 1h histogram breaks that trendline (after apex E), the first 15m bar
     * after the signal is the entry bar. SL is taken from 15m price structure.
     */
    private List<DetectedPattern> scanMacdHistTrendlineConfirmation(
            List<DetectedPattern> triangleWatching,
            List<Bar> barsW, double[] macdHistW, Map<Instant, Integer> tsToIdxW,   // 1h: trendline
            List<Bar> barsC, Map<Instant, Integer> tsToIdxC,                        // 15m: entry/SL
            double[] atrArrC, double[] rsiValuesC, double[] stochRsiKC,
            DailyIndicators dailyInd) {

        List<DetectedPattern> results = new ArrayList<>();

        for (DetectedPattern wp : triangleWatching) {
            if (wp.getPivotBTime() == null || wp.getPivotDTime() == null) continue;

            boolean bearish = !wp.isBullish();

            // ── Step 1: Trendline on 1h histogram at B and D ──────────────────
            int idxB1h = findHistogramExtreme(tsToIdxW, macdHistW, barsW, wp.getPivotBTime(), bearish);
            int idxD1h = findHistogramExtreme(tsToIdxW, macdHistW, barsW, wp.getPivotDTime(), bearish);
            if (idxB1h < 0 || idxD1h < 0 || idxD1h <= idxB1h) continue;

            double histB = macdHistW[idxB1h];
            double histD = macdHistW[idxD1h];
            double slope = (histD - histB) / (double)(idxD1h - idxB1h);

            // ── Step 2: Scan 1h bars after apex for trendline break ───────────
            int apexIdx1h = findIdxAtOrAfter(barsW, wp.getKeyLevelTime());
            if (apexIdx1h < 0) continue;
            int scanEnd1h = Math.min(barsW.size() - 1, apexIdx1h + 20); // 20 × 1h = 20h window

            Instant signalTime = null;
            double signalHist = 0;
            for (int k = apexIdx1h + 1; k <= scanEnd1h; k++) {
                double projected = histD + slope * (k - idxD1h);
                double hist = macdHistW[k];
                boolean breakout = bearish ? hist < projected : hist > projected;
                if (breakout) {
                    signalTime = barsW.get(k).getEndTime();
                    signalHist = hist;
                    break;
                }
            }
            if (signalTime == null) continue;

            // ── Step 3: Map signal to first 15m bar after the 1h signal bar ──
            int entryIdx15m = findIdxAtOrAfter(barsC, signalTime);
            if (entryIdx15m < 0 || entryIdx15m >= barsC.size()) continue;

            Bar entryBar = barsC.get(entryIdx15m);
            double entryPrice = entryBar.getOpenPrice().doubleValue(); // enter at open of first 15m bar
            double atr = entryIdx15m < atrArrC.length ? atrArrC[entryIdx15m] : 0;
            if (atr <= 0) continue;

            // ── Step 4: SL from 15m price structure (B-pivot area to entry) ──
            int slStart15m = findIdxAtOrAfter(barsC, wp.getPivotBTime());
            if (slStart15m < 0) slStart15m = 0;
            double sl = computeSl(barsC, slStart15m, entryIdx15m, bearish, atr);
            if (bearish && sl <= entryPrice) continue;
            if (!bearish && sl >= entryPrice) continue;
            if (Math.abs(entryPrice - sl) < 0.3 * atr) continue;

            double ownTarget = bearish ? entryPrice - 3 * atr : entryPrice + 3 * atr;

            results.add(DetectedPattern.builder()
                    .patternType("MACD_HIST_TL")
                    .confirmationType("MACD_TL")
                    .bullish(wp.isBullish())
                    .keyLevel(entryPrice)
                    .keyLevelTime(entryBar.getEndTime())
                    .entryPrice(entryPrice)
                    .stopLoss(sl)
                    .ownTarget(ownTarget)
                    .patternHeight(0)
                    .atr(atr)
                    .rsiAtP1(idxB1h < rsiValuesC.length ? rsiValuesC[idxB1h] : 0)
                    .rsiAtP2(idxD1h < rsiValuesC.length ? rsiValuesC[idxD1h] : 0)
                    .macdHistAtP1(histB)
                    .macdHistAtP2(histD)
                    .stochRsiK(entryIdx15m < stochRsiKC.length ? stochRsiKC[entryIdx15m] : 0)
                    .dailyRsi(dailyInd.rsiAtTs(entryBar.getEndTime()))
                    .macdHistogram(signalHist)
                    .build());
        }
        return results;
    }

    // ── Head & Shoulders Watching (5-pivot ABCDE) ───────────────────────────────

    private List<DetectedPattern> scanHnsWatching(List<ZigZagPoint> pivots, List<Bar> barsW,
            Map<Instant, Integer> tsToIdxW, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK) {
        List<DetectedPattern> results = new ArrayList<>();

        for (int i = 4; i < pivots.size(); i++) {
            ZigZagPoint pA = pivots.get(i - 4);
            ZigZagPoint pB = pivots.get(i - 3);
            ZigZagPoint pC = pivots.get(i - 2);
            ZigZagPoint pD = pivots.get(i - 1);
            ZigZagPoint pE = pivots.get(i);

            // Bullish inverse H&S: A=low(left shoulder), B=high, C=low(head), D=high, E=low(right shoulder)
            if (pA.isLow() && pB.isHigh() && pC.isLow() && pD.isHigh() && pE.isLow()) {
                processHnsWatching(pA, pB, pC, pD, pE, barsW, tsToIdxW, atrArr, rsiValues, macdHistArr, stochRsiK, results, true);
            }
            // Bearish regular H&S: A=high(left shoulder), B=low, C=high(head), D=low, E=high(right shoulder)
            if (pA.isHigh() && pB.isLow() && pC.isHigh() && pD.isLow() && pE.isHigh()) {
                processHnsWatching(pA, pB, pC, pD, pE, barsW, tsToIdxW, atrArr, rsiValues, macdHistArr, stochRsiK, results, false);
            }
        }
        return results;
    }

    private void processHnsWatching(ZigZagPoint pA, ZigZagPoint pB, ZigZagPoint pC, ZigZagPoint pD, ZigZagPoint pE,
            List<Bar> barsW, Map<Instant, Integer> tsToIdxW, double[] atrArr, double[] rsiValues,
            double[] macdHistArr, double[] stochRsiK, List<DetectedPattern> results, boolean bullish) {

        Integer aIdx = tsToIdxW.get(pA.getTimestamp());
        Integer bIdx = tsToIdxW.get(pB.getTimestamp());
        Integer cIdx = tsToIdxW.get(pC.getTimestamp());
        Integer dIdx = tsToIdxW.get(pD.getTimestamp());
        Integer eIdx = tsToIdxW.get(pE.getTimestamp());
        if (aIdx == null || bIdx == null || cIdx == null || dIdx == null || eIdx == null) return;

        double A = pA.getValue();
        double B = pB.getValue();
        double C = pC.getValue();
        double D = pD.getValue();
        double E = pE.getValue();

        double AB = Math.abs(B - A);
        double BC = Math.abs(B - C);
        double CD = Math.abs(D - C);
        double DE = Math.abs(D - E);

        if (AB <= 0 || BC <= 0) return;

        // Rule 1: 1.6×AB ≤ BC ≤ 2.61×AB (head bigger than shoulder but not too big)
        if (BC < 1.6 * AB || BC > 2.61 * AB) return;

        // Rule 2: CD ≥ 82% of BC
        if (CD < 0.82 * BC) return;

        double atrAtE = eIdx < atrArr.length ? atrArr[eIdx] : 0;
        if (atrAtE <= 0) return;

        // Rule 3: |DE − AB| ≤ ATR (symmetric shoulders)
        if (Math.abs(DE - AB) > atrAtE) return;

        // Head must be on correct side (deeper than shoulders)
        if (bullish  && C >= Math.min(A, E)) return;  // head must be BELOW both shoulder lows
        if (!bullish && C <= Math.max(A, E)) return;  // head must be ABOVE both shoulder highs

        // Neckline: line through B and D
        double necklineSlope = (D - B) / (double) Math.max(1, dIdx - bIdx);
        double necklineAtE = B + necklineSlope * (eIdx - bIdx);

        // E must be on the right side of neckline
        if (bullish  && E >= necklineAtE) return;
        if (!bullish && E <= necklineAtE) return;

        // eSymmetry = E - A (tracking, not filter)
        double eSymmetry = E - A;

        // patternHeight = distance from head to neckline (measured move target)
        double patternHeight = Math.abs(necklineAtE - C);

        double rsiAtC = cIdx < rsiValues.length ? rsiValues[cIdx] : 0;
        double rsiAtE = eIdx < rsiValues.length ? rsiValues[eIdx] : 0;
        double macdHistAtE = eIdx < macdHistArr.length ? macdHistArr[eIdx] : 0;

        // C1 target: neckline + patternHeight (full measured move from neckline)
        double ownTargetC1 = bullish ? necklineAtE + patternHeight : necklineAtE - patternHeight;

        // Emit C1 watching pattern (entry near E on lower TF)
        results.add(DetectedPattern.builder()
                .patternType(bullish ? "HNS_BULL" : "HNS_BEAR")
                .confirmationType("C1")
                .bullish(bullish)
                .keyLevel(E)
                .keyLevelTime(pE.getTimestamp())
                .entryPrice(0)
                .stopLoss(bullish ? E - atrAtE : E + atrAtE)  // SL hint for C1
                .ownTarget(ownTargetC1)
                .patternHeight(patternHeight)
                .atr(atrAtE)
                .rsiAtP1(rsiAtC)
                .rsiAtP2(rsiAtE)
                .macdHistAtP1(eSymmetry)   // reuse field to carry eSymmetry for CSV
                .macdHistAtP2(macdHistAtE)
                .stochRsiK(eIdx < stochRsiK.length ? stochRsiK[eIdx] : 0)
                .dailyRsi(0)
                .macdHistogram(macdHistAtE)
                .reversalPattern(null)
                .build());

        // Scan for F breaking neckline → emit C2 watching pattern
        int scanEnd = Math.min(barsW.size() - 1, eIdx + 40); // up to 40 × 1h bars
        for (int k = eIdx + 1; k <= scanEnd; k++) {
            Bar bar = barsW.get(k);
            double necklineAtK = B + necklineSlope * (k - bIdx);
            boolean fBreaks = bullish
                    ? bar.getHighPrice().doubleValue() >= necklineAtK
                    : bar.getLowPrice().doubleValue() <= necklineAtK;
            if (!fBreaks) continue;

            double ownTargetC2 = bullish ? necklineAtK + patternHeight : necklineAtK - patternHeight;

            // For C2: store E price in stopLoss field so the confirmation scanner can compute EF
            results.add(DetectedPattern.builder()
                    .patternType(bullish ? "HNS_BULL" : "HNS_BEAR")
                    .confirmationType("C2")
                    .bullish(bullish)
                    .keyLevel(necklineAtK)      // C2 confirmation zone = near neckline
                    .keyLevelTime(bar.getEndTime())
                    .entryPrice(0)
                    .stopLoss(E)               // reuse: stores E price so scanner can compute EF
                    .ownTarget(ownTargetC2)
                    .patternHeight(patternHeight)
                    .atr(atrAtE)
                    .pivotBTime(pE.getTimestamp())   // E time
                    .pivotDTime(bar.getEndTime())    // F time
                    .rsiAtP1(rsiAtC)
                    .rsiAtP2(rsiAtE)
                    .macdHistAtP1(eSymmetry)   // carry eSymmetry
                    .macdHistAtP2(macdHistAtE)
                    .stochRsiK(k < stochRsiK.length ? stochRsiK[k] : 0)
                    .dailyRsi(0)
                    .macdHistogram(k < macdHistArr.length ? macdHistArr[k] : 0)
                    .reversalPattern(null)
                    .build());
            break; // first F break only
        }
    }

    /**
     * Detects RSI crossover entry for triangle watching patterns on the WATCHING TF (1h).
     *
     * After the triangle apex (keyLevelTime), scan 1h bars for:
     *   bull trade: RSI crosses above 60 → rsiW[k-1] < 60 && rsiW[k] >= 60
     *   bear trade: RSI crosses below 40 → rsiW[k-1] > 40 && rsiW[k] <= 40
     *
     * The crossover candle is the SL candle:
     *   bull: SL = low of bar k
     *   bear: SL = high of bar k
     *
     * Entry = open of the first 15m bar after the 1h bar k closes.
     */
    /**
     * Triangle confirmation: scan 15m bars after the apex (point E) for a reversal candle
     * whose price range is within 1.5× the 1h ATR of the apex price.
     * Entry = open of the bar after breakout. SL = low of reversal candle (bull) or high (bear).
     * This is lighter than requiring a full DTB pattern on lower TF.
     */
    private List<DetectedPattern> scanTriangleCandleConfirmation(
            List<DetectedPattern> triangleWatching,
            List<Bar> barsC, double[] atrArrC,
            double[] rsiValuesC, double[] stochRsiKC,
            DailyIndicators dailyInd) {

        List<DetectedPattern> results = new ArrayList<>();

        for (DetectedPattern wp : triangleWatching) {
            boolean bullish = wp.isBullish();
            double apexPrice = wp.getKeyLevel();
            double atr1h = wp.getAtr();  // ATR at apex on 1h TF
            if (atr1h <= 0) continue;

            // Find start index on 15m (first bar at or after apex time)
            int apexIdx = findIdxAtOrAfter(barsC, wp.getKeyLevelTime());
            if (apexIdx < 0) continue;

            int scanEnd = Math.min(barsC.size() - 1, apexIdx + 30); // 30 × 15m = 7.5h window

            for (int i = apexIdx; i < scanEnd; i++) {
                Bar bar = barsC.get(i);

                // Bar must overlap with apex price ± 1.5 ATR
                double barLow  = bar.getLowPrice().doubleValue();
                double barHigh = bar.getHighPrice().doubleValue();
                boolean nearApex = barLow  <= apexPrice + 1.5 * atr1h
                                && barHigh >= apexPrice - 1.5 * atr1h;
                if (!nearApex) continue;

                // Detect reversal candle in the right direction
                CandlestickPatternDetector.PatternResult rev = bullish
                        ? candlestickPatternDetector.detectBullish(barsC, i)
                        : candlestickPatternDetector.detectBearish(barsC, i);
                if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

                // Wait for breakout of the reversal candle
                double breakoutLevel = rev.breakoutLevel();
                for (int j = i + 1; j < Math.min(barsC.size(), i + 10); j++) {
                    Bar entryBar = barsC.get(j);
                    boolean breakout = bullish
                            ? entryBar.getHighPrice().doubleValue() > breakoutLevel
                            : entryBar.getLowPrice().doubleValue() < breakoutLevel;
                    if (!breakout) continue;

                    double entryPrice = entryBar.getOpenPrice().doubleValue();
                    // SL = reversal candle low/high with 0.5 ATR buffer (wider to absorb noise near apex)
                    double sl = bullish
                            ? barLow  - 0.5 * atr1h   // half ATR below candle low
                            : barHigh + 0.5 * atr1h;  // half ATR above candle high

                    // Guard: SL must be on correct side with minimum distance
                    if (bullish  && sl >= entryPrice) break;
                    if (!bullish && sl <= entryPrice) break;
                    double localAtr = j < atrArrC.length ? atrArrC[j] : atr1h;
                    if (Math.abs(entryPrice - sl) < 0.3 * localAtr) break;

                    double ownTarget = bullish
                            ? entryPrice + 3 * Math.abs(entryPrice - sl)
                            : entryPrice - 3 * Math.abs(entryPrice - sl);

                    results.add(DetectedPattern.builder()
                            .patternType("TRIANGLE_CANDLE")
                            .confirmationType("CANDLE")
                            .bullish(bullish)
                            .keyLevel(entryPrice)
                            .keyLevelTime(entryBar.getEndTime())
                            .entryPrice(entryPrice)
                            .stopLoss(sl)
                            .ownTarget(ownTarget)
                            .patternHeight(0)
                            .atr(localAtr)
                            .rsiAtP1(i < rsiValuesC.length ? rsiValuesC[i] : 0)
                            .rsiAtP2(j < rsiValuesC.length ? rsiValuesC[j] : 0)
                            .macdHistAtP1(0)
                            .macdHistAtP2(0)
                            .stochRsiK(j < stochRsiKC.length ? stochRsiKC[j] : 0)
                            .dailyRsi(dailyInd.rsiAtTs(entryBar.getEndTime()))
                            .macdHistogram(0)
                            .reversalPattern(rev.pattern().name())
                            .build());
                    break; // one entry per reversal candle
                }
                if (!results.isEmpty() && results.get(results.size()-1).getKeyLevelTime() != null
                        && !results.get(results.size()-1).getKeyLevelTime().isBefore(bar.getEndTime())) {
                    break; // found first confirmation for this triangle, stop scanning
                }
            }
        }
        return results;
    }

    /**
     * H&S candle confirmation on lower TF.
     * C1: reversal candle near E price (wp.keyLevel) within ±1.5 ATR, entry on breakout.
     * C2: after F breaks neckline, retrace ≥ 38%+ATR of EF swing but still above neckline,
     *     reversal candle in retrace zone.
     */
    private List<DetectedPattern> scanHnsCandleConfirmation(
            List<DetectedPattern> hnsWatching,
            List<Bar> barsC, double[] atrArrC,
            double[] rsiValuesC, double[] stochRsiKC,
            DailyIndicators dailyInd) {

        List<DetectedPattern> results = new ArrayList<>();

        for (DetectedPattern wp : hnsWatching) {
            boolean bullish = wp.isBullish();
            boolean isC2 = "C2".equals(wp.getConfirmationType());
            double atr = wp.getAtr();
            if (atr <= 0) continue;

            if (!isC2) {
                // C1: scan 15m bars around E time for reversal candle within ePrice ± 1.5 ATR
                double ePrice = wp.getKeyLevel();
                Instant eTime = wp.getKeyLevelTime();

                int startIdx = findIdxAtOrAfter(barsC, eTime.minusSeconds(2 * 3600L));
                if (startIdx < 0) startIdx = 0;
                int scanEnd15m = findIdxAtOrAfter(barsC, eTime.plusSeconds(20 * 3600L));
                if (scanEnd15m < 0) scanEnd15m = Math.min(barsC.size() - 1, startIdx + 80);

                for (int i = startIdx; i < scanEnd15m && i < barsC.size(); i++) {
                    Bar bar = barsC.get(i);
                    double barLow  = bar.getLowPrice().doubleValue();
                    double barHigh = bar.getHighPrice().doubleValue();
                    boolean nearE = barLow <= ePrice + 1.5 * atr && barHigh >= ePrice - 1.5 * atr;
                    if (!nearE) continue;

                    CandlestickPatternDetector.PatternResult rev = bullish
                            ? candlestickPatternDetector.detectBullish(barsC, i)
                            : candlestickPatternDetector.detectBearish(barsC, i);
                    if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

                    double breakoutLevel = rev.breakoutLevel();
                    for (int j = i + 1; j < Math.min(barsC.size(), i + 10); j++) {
                        Bar entryBar = barsC.get(j);
                        boolean breakout = bullish
                                ? entryBar.getHighPrice().doubleValue() > breakoutLevel
                                : entryBar.getLowPrice().doubleValue() < breakoutLevel;
                        if (!breakout) continue;

                        double entryPrice = entryBar.getOpenPrice().doubleValue();
                        double sl = bullish ? barLow - 0.5 * atr : barHigh + 0.5 * atr;

                        if (bullish  && sl >= entryPrice) break;
                        if (!bullish && sl <= entryPrice) break;
                        double localAtr = j < atrArrC.length ? atrArrC[j] : atr;
                        if (Math.abs(entryPrice - sl) < 0.3 * localAtr) break;

                        double ownTarget = bullish
                                ? entryPrice + wp.getPatternHeight()
                                : entryPrice - wp.getPatternHeight();

                        results.add(DetectedPattern.builder()
                                .patternType("HNS_CANDLE")
                                .confirmationType("C1")
                                .bullish(bullish)
                                .keyLevel(entryPrice)
                                .keyLevelTime(entryBar.getEndTime())
                                .entryPrice(entryPrice)
                                .stopLoss(sl)
                                .ownTarget(ownTarget)
                                .patternHeight(wp.getPatternHeight())
                                .atr(localAtr)
                                .rsiAtP1(i < rsiValuesC.length ? rsiValuesC[i] : 0)
                                .rsiAtP2(j < rsiValuesC.length ? rsiValuesC[j] : 0)
                                .macdHistAtP1(wp.getMacdHistAtP1())  // carry eSymmetry
                                .macdHistAtP2(0)
                                .stochRsiK(j < stochRsiKC.length ? stochRsiKC[j] : 0)
                                .dailyRsi(dailyInd.rsiAtTs(entryBar.getEndTime()))
                                .macdHistogram(0)
                                .reversalPattern(rev.pattern().name())
                                .build());
                        break;
                    }
                    // one C1 confirmation per H&S watching pattern
                    if (!results.isEmpty() && results.get(results.size() - 1).getKeyLevelTime() != null
                            && !results.get(results.size() - 1).getKeyLevelTime().isBefore(bar.getEndTime())) {
                        break;
                    }
                }
            } else {
                // C2: after F breaks neckline (wp.keyLevelTime), scan 15m for retrace zone
                // wp.keyLevel = necklineAtF, wp.stopLoss = E price
                double necklineAtF = wp.getKeyLevel();
                double ePrice = wp.getStopLoss();  // E price stored here
                double EF = Math.abs(necklineAtF - ePrice);
                if (EF <= 0) continue;

                // Retrace zone: at least 38% of EF + 1 ATR back from neckline, but above neckline
                double minRetrace = 0.382 * EF + atr;
                // Price must be in range: [necklineAtF - minRetrace, necklineAtF] (bull)
                double retraceZoneLow  = bullish ? necklineAtF - minRetrace : necklineAtF;
                double retraceZoneHigh = bullish ? necklineAtF             : necklineAtF + minRetrace;

                Instant fTime = wp.getKeyLevelTime();
                int startIdx = findIdxAtOrAfter(barsC, fTime);
                if (startIdx < 0) continue;
                int scanEnd15m = Math.min(barsC.size() - 1, startIdx + 80);

                for (int i = startIdx; i < scanEnd15m; i++) {
                    Bar bar = barsC.get(i);
                    double barClose = bar.getClosePrice().doubleValue();
                    // Bar must be in retrace zone and above neckline (bullish) or below (bearish)
                    boolean inZone = bullish
                            ? barClose >= retraceZoneLow && barClose <= necklineAtF
                            : barClose <= retraceZoneHigh && barClose >= necklineAtF;
                    if (!inZone) continue;

                    CandlestickPatternDetector.PatternResult rev = bullish
                            ? candlestickPatternDetector.detectBullish(barsC, i)
                            : candlestickPatternDetector.detectBearish(barsC, i);
                    if (rev.pattern() == CandlestickPatternDetector.CandlePattern.NONE) continue;

                    double breakoutLevel = rev.breakoutLevel();
                    for (int j = i + 1; j < Math.min(barsC.size(), i + 10); j++) {
                        Bar entryBar = barsC.get(j);
                        boolean breakout = bullish
                                ? entryBar.getHighPrice().doubleValue() > breakoutLevel
                                : entryBar.getLowPrice().doubleValue() < breakoutLevel;
                        if (!breakout) continue;

                        double entryPrice = entryBar.getOpenPrice().doubleValue();
                        // SL = neckline - ATR (bull): below neckline means pattern failed
                        double sl = bullish ? necklineAtF - atr : necklineAtF + atr;

                        if (bullish  && sl >= entryPrice) break;
                        if (!bullish && sl <= entryPrice) break;
                        double localAtr = j < atrArrC.length ? atrArrC[j] : atr;
                        if (Math.abs(entryPrice - sl) < 0.3 * localAtr) break;

                        double ownTarget = bullish
                                ? entryPrice + wp.getPatternHeight()
                                : entryPrice - wp.getPatternHeight();

                        results.add(DetectedPattern.builder()
                                .patternType("HNS_CANDLE")
                                .confirmationType("C2")
                                .bullish(bullish)
                                .keyLevel(entryPrice)
                                .keyLevelTime(entryBar.getEndTime())
                                .entryPrice(entryPrice)
                                .stopLoss(sl)
                                .ownTarget(ownTarget)
                                .patternHeight(wp.getPatternHeight())
                                .atr(localAtr)
                                .rsiAtP1(i < rsiValuesC.length ? rsiValuesC[i] : 0)
                                .rsiAtP2(j < rsiValuesC.length ? rsiValuesC[j] : 0)
                                .macdHistAtP1(wp.getMacdHistAtP1())  // carry eSymmetry
                                .macdHistAtP2(0)
                                .stochRsiK(j < stochRsiKC.length ? stochRsiKC[j] : 0)
                                .dailyRsi(dailyInd.rsiAtTs(entryBar.getEndTime()))
                                .macdHistogram(0)
                                .reversalPattern(rev.pattern().name())
                                .build());
                        break;
                    }
                    if (!results.isEmpty() && results.get(results.size() - 1).getKeyLevelTime() != null
                            && !results.get(results.size() - 1).getKeyLevelTime().isBefore(bar.getEndTime())) {
                        break;
                    }
                }
            }
        }
        return results;
    }

    private List<DetectedPattern> scanRsiCrossoverConfirmation(
            List<DetectedPattern> triangleWatching,
            List<Bar> barsW, double[] rsiW,
            List<Bar> barsC, double[] atrArrC,
            double[] rsiValuesC, double[] stochRsiKC,
            DailyIndicators dailyInd) {

        List<DetectedPattern> results = new ArrayList<>();

        for (DetectedPattern wp : triangleWatching) {
            boolean bullish = wp.isBullish();

            // Find apex index on 1h
            int apexIdx = findIdxAtOrAfter(barsW, wp.getKeyLevelTime());
            if (apexIdx < 1) continue; // need k-1 to exist

            int scanEnd = Math.min(barsW.size() - 1, apexIdx + 30); // 30 × 1h = 30h window

            for (int k = apexIdx + 1; k <= scanEnd; k++) {
                double prevRsi = rsiW[k - 1];
                double currRsi = rsiW[k];

                boolean crossover = bullish
                        ? (prevRsi < 60.0 && currRsi >= 60.0)
                        : (prevRsi > 40.0 && currRsi <= 40.0);

                if (!crossover) continue;

                // SL is the crossover candle itself
                Bar crossBar = barsW.get(k);
                double sl = bullish
                        ? crossBar.getLowPrice().doubleValue()
                        : crossBar.getHighPrice().doubleValue();

                // Entry = open of the first 15m bar after 1h bar k closes
                Instant signal1hEnd = crossBar.getEndTime();
                int entryIdx15m = findIdxAtOrAfter(barsC, signal1hEnd);
                if (entryIdx15m < 0 || entryIdx15m >= barsC.size()) break;

                Bar entryBar = barsC.get(entryIdx15m);
                double entryPrice = entryBar.getOpenPrice().doubleValue();
                double atr = entryIdx15m < atrArrC.length ? atrArrC[entryIdx15m] : 0;

                // Guard: SL must be on correct side with minimum distance
                if (bullish  && sl >= entryPrice) break;
                if (!bullish && sl <= entryPrice) break;
                if (atr > 0 && Math.abs(entryPrice - sl) < 0.2 * atr) break;

                double ownTarget = bullish
                        ? entryPrice + 3 * Math.abs(entryPrice - sl)
                        : entryPrice - 3 * Math.abs(entryPrice - sl);

                results.add(DetectedPattern.builder()
                        .patternType("RSI_CROSS")
                        .confirmationType("RSI_CROSS")
                        .bullish(bullish)
                        .keyLevel(entryPrice)
                        .keyLevelTime(entryBar.getEndTime())
                        .entryPrice(entryPrice)
                        .stopLoss(sl)
                        .ownTarget(ownTarget)
                        .patternHeight(0)
                        .atr(atr)
                        .rsiAtP1(prevRsi)
                        .rsiAtP2(currRsi)
                        .macdHistAtP1(0)
                        .macdHistAtP2(0)
                        .stochRsiK(entryIdx15m < stochRsiKC.length ? stochRsiKC[entryIdx15m] : 0)
                        .dailyRsi(dailyInd.rsiAtTs(entryBar.getEndTime()))
                        .macdHistogram(0)
                        .reversalPattern(null)
                        .build());
                break; // one entry per triangle
            }
        }
        return results;
    }

    /** Find the index of the histogram extreme (min or max) in a ±3 bar window around the given timestamp. */
    private int findHistogramExtreme(Map<Instant, Integer> tsToIdx, double[] macdHist, List<Bar> bars,
            Instant ts, boolean findMin) {
        // Try exact match first, then scan nearby bars
        Integer base = tsToIdx.get(ts);
        int center = -1;
        if (base != null) {
            center = base;
        } else {
            // Find nearest bar whose endTime is closest to ts
            long tsEpoch = ts.getEpochSecond();
            long bestDiff = Long.MAX_VALUE;
            for (int i = 0; i < bars.size(); i++) {
                long diff = Math.abs(bars.get(i).getEndTime().getEpochSecond() - tsEpoch);
                if (diff < bestDiff) { bestDiff = diff; center = i; }
                if (diff > bestDiff) break; // bars are sorted, distance growing
            }
        }
        if (center < 0) return -1;

        int start = Math.max(0, center - 3);
        int end   = Math.min(macdHist.length - 1, center + 3);
        int extremeIdx = start;
        for (int i = start + 1; i <= end; i++) {
            if (findMin && macdHist[i] < macdHist[extremeIdx]) extremeIdx = i;
            if (!findMin && macdHist[i] > macdHist[extremeIdx]) extremeIdx = i;
        }
        return extremeIdx;
    }

    /** Find the first bar index whose endTime is >= ts. Returns -1 if not found. */
    private int findIdxAtOrAfter(List<Bar> bars, Instant ts) {
        for (int i = 0; i < bars.size(); i++) {
            if (!bars.get(i).getEndTime().isBefore(ts)) return i;
        }
        return -1;
    }

    /** Scan window in bars for confirmation TF (default 80 * 15m-equivalent bars). */
    private int confirmWindowBarsForWp(DetectedPattern wp) {
        return 80; // 80 × 15m bars ≈ 20h; parameterized if needed
    }

    /** SL = highest high (bearish) or lowest low (bullish) from slStart to entryIdx, with 0.1-ATR buffer. */
    private double computeSl(List<Bar> bars, int slStart, int entryIdx, boolean bearish, double atr) {
        double sl = bearish ? Double.MIN_VALUE : Double.MAX_VALUE;
        for (int m = slStart; m <= entryIdx; m++) {
            if (bearish) sl = Math.max(sl, bars.get(m).getHighPrice().doubleValue());
            else         sl = Math.min(sl, bars.get(m).getLowPrice().doubleValue());
        }
        return bearish ? sl + 0.1 * atr : sl - 0.1 * atr;
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
                "stoch_rsi_k_15m,daily_rsi,watching_pattern_height,confirm_pattern_height,target_eventually_hit,post_exit_max_retrace_pct,e_symmetry\n";
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

    /** Compute Bollinger Bands. Returns double[3][n]: [0]=upper, [1]=middle, [2]=lower. */
    private double[][] computeBollingerBands(List<Bar> bars, int period, double mult) {
        int n = bars.size();
        double[] upper  = new double[n];
        double[] middle = new double[n];
        double[] lower  = new double[n];
        for (int i = 0; i < n; i++) {
            int start = Math.max(0, i - period + 1);
            int count = i - start + 1;
            double sum = 0;
            for (int j = start; j <= i; j++) sum += bars.get(j).getClosePrice().doubleValue();
            double sma = sum / count;
            double variance = 0;
            for (int j = start; j <= i; j++) {
                double d = bars.get(j).getClosePrice().doubleValue() - sma;
                variance += d * d;
            }
            double stddev = Math.sqrt(variance / count);
            upper[i]  = sma + mult * stddev;
            middle[i] = sma;
            lower[i]  = sma - mult * stddev;
        }
        return new double[][]{upper, middle, lower};
    }

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
        boolean targetEventuallyHit;
        double  postExitMaxRetracePct;  // deepest retrace from peak after early exit (for analysis)
        double  eSymmetry;   // E - A price diff (H&S shoulder symmetry tracking; 0 for non-H&S)

        public String toCsvRow() {
            return String.format(Locale.US,
                "%s,%s,%s,%s,%s,%s,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%d,%.2f,%.2f,%s,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%.2f,%s,%.2f,%.2f",
                entryTime, symbol, watchingPattern, watchingTf, confirmPattern, confirmTf, confirmType,
                entryPrice, stopLoss, watchingTarget, confirmOwnTarget, rrWatching, keyLevel,
                result, barsToResult, pnlPct, exitPrice, exitReason,
                rsiAtP1, rsiAtP2, macdHistAtP1, macdHistAtP2, stochRsiK15m, dailyRsi,
                watchingPatternHeight, confirmPatternHeight, targetEventuallyHit, postExitMaxRetracePct, eSymmetry);
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
