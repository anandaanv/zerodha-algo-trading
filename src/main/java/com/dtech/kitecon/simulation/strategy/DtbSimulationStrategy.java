package com.dtech.kitecon.simulation.strategy;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagParams;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.backtest.DetectedPattern;
import com.dtech.kitecon.backtest.PatternComboBacktestService;
import com.dtech.kitecon.backtest.PatternComboBacktestService.DailyIndicators;
import com.dtech.kitecon.patternscanner.PatternDto;
import com.dtech.kitecon.patternscanner.TradeFilterClient;
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

    @Value("${trade.filter.threshold:0.82}")
    private double mlThreshold;

    /** Track processed pivot timestamps per symbol to avoid duplicate signals */
    private final Map<String, Set<Instant>> processedPivots = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, ZigZagParams> cachedParams = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Integer> lastZigZagBarCount = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<ZigZagPoint>> cachedPivots = new java.util.concurrent.ConcurrentHashMap<>();
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
    }

    @Override
    public List<TradeSignal> scan(SimulationContext ctx, String symbol, BarSeries truncatedSeries) {
        try {
            int barCount = truncatedSeries.getBarCount();
            if (barCount < 30) return List.of();

            // Optimization: only rerun ZigZag every N bars
            int lastCount = lastZigZagBarCount.getOrDefault(symbol, 0);
            if (barCount - lastCount < ZIGZAG_RECOMPUTE_INTERVAL && cachedPivots.containsKey(symbol)) {
                return List.of();
            }

            // Compute ZigZag
            ZigZagParams params = cachedParams.computeIfAbsent(symbol, s -> {
                Interval interval = Interval.valueOf(ctx.getTimeframe());
                ZigZagParams base = zigZagService.resolveParams(s, interval);
                return ZigZagParams.ofDefaults(base.getAtrLength(), base.getAtrMult(),
                        base.getPctMin(), base.getHysteresis(), base.getMinBarsBetweenPivots(),
                        base.isDynamicPctEnabled(), base.getVolMult(), base.getRvolWindow(),
                        ZigZagParams.Mode.BACKTEST);
            });

            List<ZigZagPoint> pivots = zigZagService.detect(truncatedSeries, params);
            cachedPivots.put(symbol, pivots);
            lastZigZagBarCount.put(symbol, barCount);

            if (pivots.size() < 5) return List.of();

            // Check if latest pivot already processed
            Instant latestPivotTime = pivots.get(pivots.size() - 1).getTimestamp();
            Set<Instant> processed = processedPivots.computeIfAbsent(symbol, s -> new HashSet<>());
            if (processed.contains(latestPivotTime)) return List.of();

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

            // Detect ALL pattern types (same as live PatternScanService.scan)
            List<DetectedPattern> patterns = new ArrayList<>();
            patterns.addAll(patternService.scanDtbWatchingPublic(pivots, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK));
            patterns.addAll(patternService.scanTriangleWatchingPublic(pivots, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK));
            patterns.addAll(patternService.scanHnsWatchingPublic(pivots, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK));
            patterns.addAll(patternService.scanTrendlineBreakoutWatchingPublic(pivots, bars, tsToIdx, atrArr, rsiValues, macdHistArr, stochRsiK));

            // Filter to patterns at the latest pivot only
            patterns.removeIf(p -> {
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

                // Build signal — entry at neckline (keyLevel), stop at 2×ATR, measured-move target
                boolean bullish = p.isBullish();
                double entry = p.getKeyLevel();
                double atr = p.getAtr();
                double stopLoss = bullish ? entry - 2.0 * atr : entry + 2.0 * atr;
                double target = p.getOwnTarget();
                double height = p.getPatternHeight();

                // In simulation, we assume entry at the current bar close (neckline already broken)
                Bar lastBar = bars.get(bars.size() - 1);
                double fillPrice = lastBar.getClosePrice().doubleValue();

                TradeSignal signal = TradeSignal.builder()
                        .symbol(symbol)
                        .direction(bullish ? TradeDirection.LONG : TradeDirection.SHORT)
                        .patternType(p.getPatternType())
                        .entryPrice(BigDecimal.valueOf(fillPrice))
                        .stopLoss(BigDecimal.valueOf(stopLoss))
                        .target(BigDecimal.valueOf(target))
                        .neckline(BigDecimal.valueOf(entry))
                        .patternHeight(BigDecimal.valueOf(height))
                        .rrRatio(BigDecimal.valueOf(rrRatio).setScale(2, RoundingMode.HALF_UP))
                        .mlScore(BigDecimal.valueOf(mlScore).setScale(4, RoundingMode.HALF_UP))
                        .timeframe(ctx.getTimeframe())
                        .status(TradeStatus.ACTIVE)
                        .strategyType(StrategyType.DTB)
                        .stochRsiK(BigDecimal.valueOf(p.getStochRsiK()).setScale(4, RoundingMode.HALF_UP))
                        .build();

                signals.add(signal);
                processed.add(latestPivotTime);
                log.info("[SimDTB] {} {} {} entry={} SL={} T={} ML={:.3f}",
                        symbol, p.getPatternType(), bullish ? "LONG" : "SHORT",
                        fillPrice, stopLoss, target, mlScore);
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

        for (TradeSignal sig : openPositions) {
            if (!symbol.equals(sig.getSymbol())) continue;
            if (sig.getStrategyType() != StrategyType.DTB) continue;
            try {
                ExitStrategy strategy = exitStrategyRouter.getStrategy(sig.getStrategyType());
                BigDecimal ltp = BigDecimal.valueOf(sig.getDirection() == TradeDirection.LONG ? high : low);
                ExitDecision decision = strategy.evaluate(sig, ltp, ltp);

                if (decision.action() == ExitDecision.Action.EXIT) {
                    double exitPrice = decision.exitPrice().doubleValue();
                    double pnlPct = computePnl(sig, exitPrice);
                    exits.add(new ExitResult(sig, decision.exitReason().name(), exitPrice, pnlPct));
                }
            } catch (Exception e) {
                log.warn("[SimDTB] checkExits error for {}: {}", symbol, e.toString(), e);
            }
        }
        return exits;
    }

    private double computePnl(TradeSignal sig, double exitPrice) {
        double entry = sig.getEntryPrice().doubleValue();
        return sig.getDirection() == TradeDirection.LONG
                ? (exitPrice - entry) / entry * 100
                : (entry - exitPrice) / entry * 100;
    }
}
