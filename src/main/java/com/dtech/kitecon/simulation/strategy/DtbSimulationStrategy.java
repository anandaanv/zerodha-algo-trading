package com.dtech.kitecon.simulation.strategy;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.backtest.CandlestickPatternDetector;
import com.dtech.kitecon.backtest.DetectedPattern;
import com.dtech.kitecon.backtest.PatternComboBacktestService;
import com.dtech.kitecon.backtest.PatternComboBacktestService.DailyIndicators;
import com.dtech.kitecon.patternscanner.PatternDto;
import com.dtech.kitecon.patternscanner.TradeFilterClient;
import com.dtech.kitecon.simulation.CandidatePivotZigZag;
import com.dtech.kitecon.simulation.SimulationContext;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.StrategyType;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.strategy.ExitDecision;
import com.dtech.kitecon.trade.strategy.ExitStrategy;
import com.dtech.kitecon.trade.strategy.ExitStrategyRouter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

/**
 * DTB (Double Top/Bottom + all pattern types) simulation strategy.
 * Reuses existing PatternComboBacktestService for detection,
 * TradeFilterClient for ML scoring, and DtbExitStrategy for exits.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DtbSimulationStrategy implements SimulationStrategy {

    private final ZigZagService zigZagService;
    private final PatternComboBacktestService patternService;
    private final TradeFilterClient tradeFilterClient;
    private final ExitStrategyRouter exitStrategyRouter;
    private final CandlestickPatternDetector candlePatternDetector;

    @Value("${trade.filter.threshold:0.82}")
    private double mlThreshold;

    private static final int MAX_BARS_TO_ENTRY_CONFIRMATION = 20;
    private static final int MAX_BARS_TO_EXIT = 10;

    /** Track processed pivot timestamps per symbol to avoid duplicate signals */
    private final Map<String, Set<Instant>> processedPivots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, ZigZagParams> cachedParams = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> lastZigZagBarCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<ZigZagPoint>> cachedPivots = new java.util.concurrent.ConcurrentHashMap<>();
    /** CandidatePivotZigZag per symbol for trailing-extreme-based DTB/HNS detection */
    private final Map<String, CandidatePivotZigZag> candidatePivotZigZags = new java.util.concurrent.ConcurrentHashMap<>();
    /** Track last processed bar count per symbol for incremental ZigZag */
    private final Map<String, Integer> lastProcessedBarCount = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int ZIGZAG_RECOMPUTE_INTERVAL = 4;

    @Override
    public String getStrategyType() {
        return "DTB";
    }

    @Override
    public void reset() {
        processedPivots.clear();
        cachedParams.clear();
        lastZigZagBarCount.clear();
        cachedPivots.clear();
        candidatePivotZigZags.clear();
        lastProcessedBarCount.clear();
    }

    @Override
    public List<TradeSignal> scan(SimulationContext ctx, String symbol, BarSeries truncatedSeries) {
        try {
            int barCount = truncatedSeries.getBarCount();
            if (barCount < 30) return List.of();

            // Build bars list and index map
            List<Bar> bars = new ArrayList<>();
            for (int i = 0; i < truncatedSeries.getBarCount(); i++) bars.add(truncatedSeries.getBar(i));
            Map<Instant, Integer> tsToIdx = new HashMap<>();
            for (int i = 0; i < bars.size(); i++) tsToIdx.put(bars.get(i).getEndTime(), i);

            // Compute indicators
            double[] atrArr = patternService.computeAtrPublic(bars, 14);
            double[] rsiValues = patternService.computeRsiPublic(truncatedSeries, 14);
            double[] macdHistArr = patternService.computeMacdHistPublic(truncatedSeries);
            double[] stochRsiK = patternService.computeStochRsiKPublic(rsiValues);

            // 1. Get or create CandidatePivotZigZag for this symbol
            ZigZagParams params = cachedParams.computeIfAbsent(symbol, s -> {
                Interval interval = Interval.valueOf(ctx.getTimeframe());
                ZigZagParams base = zigZagService.resolveParams(s, interval);
                return ZigZagParams.ofDefaults(base.getAtrLength(), base.getAtrMult(),
                        base.getPctMin(), base.getHysteresis(), base.getMinBarsBetweenPivots(),
                        base.isDynamicPctEnabled(), base.getVolMult(), base.getRvolWindow(),
                        ZigZagParams.Mode.BACKTEST);
            });

            CandidatePivotZigZag cpzz = candidatePivotZigZags.computeIfAbsent(symbol, s -> new CandidatePivotZigZag(params));

            // 2. Process only NEW bars since last call
            int lastProcessed = lastProcessedBarCount.getOrDefault(symbol, 0);
            for (int i = lastProcessed; i < barCount; i++) {
                cpzz.processBar(truncatedSeries, i);
            }
            lastProcessedBarCount.put(symbol, barCount);

            // 3. Get pivots with trailing extreme
            Bar currentBar = bars.get(bars.size() - 1);
            List<ZigZagPoint> pivotsWithTrailingExtreme = cpzz.getPivotsWithTrailingExtreme(currentBar);
            if (pivotsWithTrailingExtreme.size() < 3) return List.of();

            // Detect DTB/HNS patterns using trailing extreme (new detector)
            List<DetectedPattern> patterns = new ArrayList<>();
            patterns.addAll(patternService.scanDtbHnsCandidatePublic(pivotsWithTrailingExtreme, truncatedSeries,
                    atrArr, rsiValues, macdHistArr, stochRsiK, tsToIdx));

            // Keep Triangle and Trendline Breakout using confirmed pivots (old detectors)
            List<ZigZagPoint> confirmedPivots = cpzz.getConfirmedPivots();
            if (confirmedPivots.size() >= 5) {
                patterns.addAll(patternService.scanTriangleWatchingPublic(confirmedPivots, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK));
                patterns.addAll(patternService.scanTrendlineBreakoutWatchingPublic(confirmedPivots, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK));
            }

            // Filter Triangle/TLB patterns to recent bars only (keep DTB/HNS unfiltered — they emit at trailing extreme)
            Instant latestPivotTime = pivotsWithTrailingExtreme.get(pivotsWithTrailingExtreme.size() - 1).getTimestamp();
            patterns.removeIf(p -> {
                if ("DOUBLE_BOTTOM".equals(p.getPatternType()) || "DOUBLE_TOP".equals(p.getPatternType()) ||
                    "HNS_BULL".equals(p.getPatternType()) || "HNS_BEAR".equals(p.getPatternType())) {
                    return false; // Keep DTB/HNS (no time filter)
                }
                if ("TRENDLINE_BREAKOUT".equals(p.getPatternType())) {
                    int lastBarIdx = bars.size() - 1;
                    Instant recentCutoff = lastBarIdx >= 5 ? bars.get(lastBarIdx - 5).getEndTime() : bars.get(0).getEndTime();
                    return p.getKeyLevelTime().isBefore(recentCutoff);
                }
                return !latestPivotTime.equals(p.getKeyLevelTime());
            });

            if (patterns.isEmpty()) return List.of();

            // Compute multi-TF indicators for ML scoring
            DailyIndicators watchingInd = patternService.computeDailyIndicators(truncatedSeries);

            List<TradeSignal> signals = new ArrayList<>();
            for (DetectedPattern p : patterns) {
                // Prod-style dedup: check if existing OPEN signal has same patternType + keyLevel within 0.5%
                List<TradeSignal> openSignals = ctx.getOpenPositions().stream()
                        .filter(sig -> symbol.equals(sig.getSymbol()))
                        .toList();
                boolean isDuplicate = false;
                for (TradeSignal existing : openSignals) {
                    if (existing.getPatternType() != null && existing.getPatternType().equals(p.getPatternType())) {
                        if (existing.getNeckline() != null) {
                            double keyLevel = p.getKeyLevel();
                            double necklineDiff = Math.abs(existing.getNeckline().doubleValue() - keyLevel) / keyLevel;
                            if (necklineDiff < 0.005) {
                                isDuplicate = true;
                                break;
                            }
                        }
                    }
                }
                if (isDuplicate) {
                    log.debug("[SimDTB] Duplicate pattern detected for {} {}: skipping", symbol, p.getPatternType());
                    continue;
                }

                // Build PatternDto for ML scoring
                double rrRatio = p.getPatternHeight() > 0 && p.getAtr() > 0
                        ? p.getPatternHeight() / (2.0 * p.getAtr()) : 1.0;

                PatternDto dto = PatternDto.builder()
                        .patternType(p.getPatternType())
                        .rrRatio(rrRatio)
                        .patternHeight(p.getPatternHeight())
                        .rsiAtP1(p.getRsiAtP1())
                        .rsiAtP2(p.getRsiAtP2())
                        .macdHistAtP1(p.getMacdHistAtP1())
                        .macdHistAtP2(p.getMacdHistAtP2())
                        .stochRsiK(p.getStochRsiK())
                        .build();

                Instant ts = p.getKeyLevelTime();
                double mlScore = tradeFilterClient.score(dto, p.getPatternType(),
                        watchingInd.rsiAtTs(ts),      // dailyRsi (using watching as proxy in sim)
                        watchingInd.adxAtTs(ts),       // dailyAdx
                        watchingInd.adxEmaAtTs(ts),    // dailyAdxEma
                        watchingInd.adxAtTs(ts),       // adxWatching
                        watchingInd.adxEmaAtTs(ts),    // adxWatchingEma
                        0.0, 0.0,                       // adxConfirm, adxConfirmEma (no confirm TF in sim)
                        watchingInd.macdLineAtTs(ts),
                        watchingInd.macdSignalAtTs(ts),
                        watchingInd.bbWidthAtTs(ts),
                        watchingInd.bbPctBAtTs(ts),
                        0.0, 0.0, 0.0, 0.0,             // daily MACD/BB (using watching proxy)
                        watchingInd.bbExpandingAtTs(ts, 5),
                        watchingInd.bbAlignedAtTs(ts, 5, p.isBullish()),
                        watchingInd.rsiSlopeAtTs(ts, 5),
                        watchingInd.macdHistSlopeAtTs(ts, 5),
                        watchingInd.adxSlopeAtTs(ts, 5));

                if (mlScore < mlThreshold) {
                    log.debug("[SimDTB] {} {} rejected by ML: {:.3f} < {}", symbol, p.getPatternType(), mlScore, mlThreshold);
                    continue;
                }

                // Build signal with WATCHING_ENTRY status (entry will be confirmed later)
                boolean bullish = p.isBullish();
                double target = p.getOwnTarget();
                double height = p.getPatternHeight();
                double structuralSL = p.getStopLoss();

                Bar lastBar = bars.get(bars.size() - 1);
                Instant now = lastBar.getEndTime();

                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .direction(bullish ? TradeDirection.LONG : TradeDirection.SHORT)
                        .patternType(p.getPatternType())
                        .entryPrice(BigDecimal.ZERO)  // Will be set when entry is confirmed
                        .stopLoss(BigDecimal.valueOf(structuralSL))  // Use structural SL from detector
                        .target(BigDecimal.valueOf(target))
                        .neckline(BigDecimal.valueOf(p.getKeyLevel()))
                        .patternHeight(BigDecimal.valueOf(height))
                        .rrRatio(BigDecimal.valueOf(rrRatio).setScale(2, RoundingMode.HALF_UP))
                        .mlScore(BigDecimal.valueOf(mlScore).setScale(4, RoundingMode.HALF_UP))
                        .timeframe(ctx.getTimeframe())
                        .status(TradeStatus.WATCHING_ENTRY)  // Entry not yet confirmed
                        .strategyType(StrategyType.DTB)
                        .stochRsiK(BigDecimal.valueOf(p.getStochRsiK()).setScale(4, RoundingMode.HALF_UP))
                        .breakoutLevel(BigDecimal.ZERO)  // Will be set when reversal candle is found
                        .pivotP0(BigDecimal.valueOf(p.getPivotP0()))
                        .pivotP1(BigDecimal.valueOf(p.getPivotP1()))
                        .pivotP2(BigDecimal.valueOf(p.getPivotP2()))
                        .pivotP3(p.getPivotP3() != null ? BigDecimal.valueOf(p.getPivotP3()) : null)
                        .signalTime(now)
                        .barsInTrade(0)
                        .build();

                signals.add(signal);
                log.info("[SimDTB] {} {} {} waiting for entry confirmation with SL={} T={} ML={:.3f}",
                        symbol, p.getPatternType(), bullish ? "LONG" : "SHORT",
                        structuralSL, target, mlScore);
            }
            return signals;

        } catch (Exception e) {
            log.warn("[SimDTB] scan error for {}: {}", symbol, e.toString(), e);
            return List.of();
        }
    }

    @Override
    public List<ExitResult> checkExits(SimulationContext ctx, List<TradeSignal> openPositions,
                                        String symbol, Bar currentBar, BarSeries truncatedSeries, BarSeries exitSeries5m) {
        List<ExitResult> exits = new ArrayList<>();
        double high = currentBar.getHighPrice().doubleValue();
        double low = currentBar.getLowPrice().doubleValue();
        double close = currentBar.getClosePrice().doubleValue();

        // Build bars list for reversal candle detection
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < truncatedSeries.getBarCount(); i++) {
            bars.add(truncatedSeries.getBar(i));
        }

        for (TradeSignal sig : openPositions) {
            if (!symbol.equals(sig.getSymbol())) continue;
            if (sig.getStrategyType() != StrategyType.DTB) continue;

            try {
                String patternType = sig.getPatternType();
                boolean isDtbOrHns = "DOUBLE_BOTTOM".equals(patternType) || "DOUBLE_TOP".equals(patternType)
                                  || "HNS_BULL".equals(patternType) || "HNS_BEAR".equals(patternType);

                if (!isDtbOrHns) {
                    // Non-DTB/HNS patterns (Triangle, TLB): use ExitStrategyRouter
                    ExitStrategy strategy = exitStrategyRouter.getStrategy(sig.getStrategyType());
                    BigDecimal ltp = BigDecimal.valueOf(sig.getDirection() == TradeDirection.LONG ? high : low);
                    ExitDecision decision = strategy.evaluate(sig, ltp, ltp);

                    if (decision.action() == ExitDecision.Action.EXIT) {
                        double exitPrice = decision.exitPrice().doubleValue();
                        double pnlPct = computePnl(sig, exitPrice);
                        exits.add(new ExitResult(sig, decision.exitReason().name(), exitPrice, pnlPct));
                    }
                    continue;
                }

                // DTB/HNS entry confirmation and exit logic
                if (sig.getStatus() == TradeStatus.WATCHING_ENTRY) {
                    ExitResult entryResult = confirmDtbHnsEntry(sig, bars, currentBar, close);
                    if (entryResult != null) {
                        exits.add(entryResult);
                    }
                } else if (sig.getStatus() == TradeStatus.ACTIVE) {
                    ExitResult exitResult = checkDtbHnsExit(sig, currentBar, high, low, close);
                    if (exitResult != null) {
                        exits.add(exitResult);
                    }
                }

            } catch (Exception e) {
                log.warn("[SimDTB] checkExits error for {}: {}", symbol, e.toString(), e);
            }
        }
        return exits;
    }

    /**
     * Confirm DTB/HNS entry by finding reversal candle in retrace zone and subsequent break.
     * Returns an ExitResult if entry times out, null otherwise (signal will be updated in ctx).
     */
    private ExitResult confirmDtbHnsEntry(TradeSignal sig, List<Bar> bars, Bar currentBar, double close) {
        BigDecimal breakoutLevel = sig.getBreakoutLevel();
        boolean isBullish = sig.getDirection() == TradeDirection.LONG;

        if (breakoutLevel == null || breakoutLevel.compareTo(BigDecimal.ZERO) <= 0) {
            // Step 1: Look for reversal candle in retrace zone
            BigDecimal pivotP1 = sig.getPivotP1();
            BigDecimal pivotP2 = sig.getPivotP2();

            if (pivotP1 == null || pivotP2 == null || pivotP1.compareTo(BigDecimal.ZERO) == 0 || pivotP2.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }

            String patternType = sig.getPatternType();

            // Compute retrace zone bounds
            BigDecimal zoneMin, zoneMax;
            if ("DOUBLE_BOTTOM".equals(patternType)) {
                BigDecimal diff = pivotP1.subtract(pivotP2);
                zoneMin = pivotP2.add(diff.multiply(BigDecimal.valueOf(0.01)));
                zoneMax = pivotP2.add(diff.multiply(BigDecimal.valueOf(0.39)));
            } else if ("DOUBLE_TOP".equals(patternType)) {
                BigDecimal diff = pivotP2.subtract(pivotP1);
                zoneMin = pivotP2.subtract(diff.multiply(BigDecimal.valueOf(0.39)));
                zoneMax = pivotP2.subtract(diff.multiply(BigDecimal.valueOf(0.01)));
            } else if ("HNS_BEAR".equals(patternType)) {
                BigDecimal diff = pivotP1.subtract(pivotP2);
                zoneMin = pivotP1.subtract(diff.multiply(BigDecimal.valueOf(0.61)));
                zoneMax = pivotP1.subtract(diff.multiply(BigDecimal.valueOf(0.23)));
            } else if ("HNS_BULL".equals(patternType)) {
                BigDecimal diff = pivotP2.subtract(pivotP1);
                zoneMin = pivotP1.add(diff.multiply(BigDecimal.valueOf(0.23)));
                zoneMax = pivotP1.add(diff.multiply(BigDecimal.valueOf(0.61)));
            } else {
                return null;
            }

            // Scan back up to 20 bars from signal creation
            int signalBarIdx = -1;
            for (int i = 0; i < bars.size(); i++) {
                if (bars.get(i).getEndTime().equals(sig.getSignalTime())) {
                    signalBarIdx = i;
                    break;
                }
            }

            if (signalBarIdx < 0) {
                signalBarIdx = Math.max(0, bars.size() - 21);  // Fallback to last 20 bars
            }

            int searchEndIdx = Math.min(signalBarIdx + MAX_BARS_TO_ENTRY_CONFIRMATION, bars.size() - 1);

            // Look for reversal candle in zone
            for (int i = signalBarIdx + 1; i <= searchEndIdx; i++) {
                Bar bar = bars.get(i);
                double midpoint = (bar.getHighPrice().doubleValue() + bar.getLowPrice().doubleValue()) / 2.0;

                // Check if midpoint is in zone
                if (midpoint >= zoneMin.doubleValue() && midpoint <= zoneMax.doubleValue()) {
                    CandlestickPatternDetector.PatternResult result;
                    if (isBullish) {
                        result = candlePatternDetector.detectBullish(bars, i);
                    } else {
                        result = candlePatternDetector.detectBearish(bars, i);
                    }

                    if (result.pattern() != CandlestickPatternDetector.CandlePattern.NONE) {
                        // Reversal candle found — set breakoutLevel and entryValidUntil
                        sig.setBreakoutLevel(BigDecimal.valueOf(result.breakoutLevel()));
                        sig.setEntryValidUntil(currentBar.getEndTime().plusSeconds(5 * 3600));  // ~5 bars from now
                        return null;  // Don't exit, wait for break
                    }
                }
            }

            // No reversal candle found in 20 bars — entry timeout
            if (bars.size() - 1 - signalBarIdx >= MAX_BARS_TO_ENTRY_CONFIRMATION) {
                log.info("[SimDTB] Entry timeout for signal {} {}: no reversal candle in 20 bars",
                        sig.getId(), sig.getSymbol());
                return new ExitResult(sig, "ENTRY_TIMEOUT", close, 0.0);
            }

            return null;  // Still waiting for reversal candle
        } else {
            // Step 2: breakoutLevel found, wait for break
            double blevel = breakoutLevel.doubleValue();

            // Check if current bar breaks the level
            if (isBullish && close > blevel) {
                // Entry confirmed for LONG
                sig.setEntryPrice(breakoutLevel);
                sig.setStatus(TradeStatus.ACTIVE);
                sig.setBarsInTrade(0);
                return null;  // Don't exit, entry just activated
            } else if (!isBullish && close < blevel) {
                // Entry confirmed for SHORT
                sig.setEntryPrice(breakoutLevel);
                sig.setStatus(TradeStatus.ACTIVE);
                sig.setBarsInTrade(0);
                return null;  // Don't exit, entry just activated
            }

            // Not yet broken — check if entry valid window expired
            if (sig.getEntryValidUntil() != null && currentBar.getEndTime().isAfter(sig.getEntryValidUntil())) {
                log.info("[SimDTB] Entry timeout for signal {} {}: breakout not confirmed in time window",
                        sig.getId(), sig.getSymbol());
                return new ExitResult(sig, "ENTRY_TIMEOUT", close, 0.0);
            }

            // Increment bars waiting for break
            if (sig.getBarsInTrade() == null) sig.setBarsInTrade(0);
            sig.setBarsInTrade(sig.getBarsInTrade() + 1);
            return null;  // Still waiting
        }
    }

    /**
     * Check DTB/HNS exit: target vs SL within 10 bars.
     * Returns ExitResult if exit triggered, null otherwise (signal updated in ctx).
     */
    private ExitResult checkDtbHnsExit(TradeSignal sig, Bar currentBar, double high, double low, double close) {
        BigDecimal target = sig.getTarget();
        BigDecimal stopLoss = sig.getStopLoss();
        boolean isBullish = sig.getDirection() == TradeDirection.LONG;

        // Increment bars in trade
        if (sig.getBarsInTrade() == null) sig.setBarsInTrade(0);
        sig.setBarsInTrade(sig.getBarsInTrade() + 1);

        if (isBullish) {
            // LONG: check target and SL
            if (target != null && target.compareTo(BigDecimal.ZERO) > 0 && high >= target.doubleValue()) {
                double pnlPct = (target.doubleValue() - sig.getEntryPrice().doubleValue()) / sig.getEntryPrice().doubleValue() * 100;
                return new ExitResult(sig, "TARGET_HIT", target.doubleValue(), pnlPct);
            }
            if (stopLoss != null && stopLoss.compareTo(BigDecimal.ZERO) > 0 && low <= stopLoss.doubleValue()) {
                double pnlPct = (stopLoss.doubleValue() - sig.getEntryPrice().doubleValue()) / sig.getEntryPrice().doubleValue() * 100;
                return new ExitResult(sig, "STOP_LOSS", stopLoss.doubleValue(), pnlPct);
            }
        } else {
            // SHORT: check target and SL
            if (target != null && target.compareTo(BigDecimal.ZERO) > 0 && low <= target.doubleValue()) {
                double pnlPct = (sig.getEntryPrice().doubleValue() - target.doubleValue()) / sig.getEntryPrice().doubleValue() * 100;
                return new ExitResult(sig, "TARGET_HIT", target.doubleValue(), pnlPct);
            }
            if (stopLoss != null && stopLoss.compareTo(BigDecimal.ZERO) > 0 && high >= stopLoss.doubleValue()) {
                double pnlPct = (sig.getEntryPrice().doubleValue() - stopLoss.doubleValue()) / sig.getEntryPrice().doubleValue() * 100;
                return new ExitResult(sig, "STOP_LOSS", stopLoss.doubleValue(), pnlPct);
            }
        }

        // Timeout at 10 bars
        if (sig.getBarsInTrade() >= MAX_BARS_TO_EXIT) {
            double pnlPct = computePnl(sig, close);
            return new ExitResult(sig, "TIMEOUT", close, pnlPct);
        }

        return null;  // Still in trade
    }

    private double computePnl(TradeSignal sig, double exitPrice) {
        double entry = sig.getEntryPrice().doubleValue();
        return sig.getDirection() == TradeDirection.LONG
                ? (exitPrice - entry) / entry * 100
                : (entry - exitPrice) / entry * 100;
    }
}
