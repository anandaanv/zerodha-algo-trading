package com.dtech.aitrader.service;

import com.dtech.aitrader.data.AiLevels;
import com.dtech.aitrader.model.PatternSignal;
import com.dtech.aitrader.repository.AiLevelsRepository;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import com.dtech.ta.patterns.classic.DtbDetector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBarSeriesBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Walk-forward simulation of DTB pattern detection gated by AI pattern agent.
 * NO LOOK-AHEAD: builds subseries bar-by-bar and evaluates DTB signals in real-time.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalkForwardAiSimService {
    private final ChartDataService chartDataService;
    private final DtbDetector dtbDetector;
    private final PatternAgentService patternAgentService;
    private final LevelsAgentService levelsAgentService;
    private final AiLevelsRepository aiLevelsRepository;
    private final AiTraderConfigService configService;

    public record TradeResult(
            Instant signalTime,
            String patternType,
            String direction,
            String verdict,
            Double entryPrice,
            Double slPrice,
            Double targetPrice,
            Double exitPrice,
            Double pnlPct,
            String reasoning
    ) {}

    public record WalkForwardResult(
            int totalSignals,
            int aiTrades,
            int aiNoTrades,
            double aiTotalPnlPct,
            double baselinePnlPct,
            List<TradeResult> trades,
            boolean truncated
    ) {}

    /**
     * Run walk-forward simulation on INFY or other symbol with NO LOOK-AHEAD.
     *
     * @param symbol    e.g. "INFY"
     * @param fromDate  Start date
     * @param toDate    End date
     * @param userId    User ID for LLM calls
     * @return Walk-forward results
     */
    public WalkForwardResult run(String symbol, LocalDate fromDate, LocalDate toDate, Long userId) {
        log.info("Starting walk-forward simulation: symbol={}, from={}, to={}, userId={}",
                symbol, fromDate, toDate, userId);

        // Load all hourly bars for the date range
        List<OhlcBarDTO> allBars = chartDataService.getBars(symbol, "OneHour", null, null, false);
        if (allBars.isEmpty()) {
            throw new IllegalStateException("No hourly bars available for " + symbol);
        }

        // Filter bars within date range
        long fromMillis = fromDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli();
        long toMillis = toDate.atStartOfDay(ZoneId.of("UTC")).toInstant().plusSeconds(86400).toEpochMilli();
        List<OhlcBarDTO> bars = allBars.stream()
                .filter(b -> b.getTime() * 1000L >= fromMillis && b.getTime() * 1000L < toMillis)
                .collect(Collectors.toList());

        log.info("Loaded {} bars for {} between {} and {}", bars.size(), symbol, fromDate, toDate);

        if (bars.size() < 200) {
            throw new IllegalStateException("Not enough bars (" + bars.size() + ") for walk-forward; need 200+ for warmup");
        }

        int maxAiCalls = configService.getInt("walkforward.max_ai_calls", 80);
        int aiCallCount = 0;
        int totalSignals = 0;
        int aiTrades = 0;
        int aiNoTrades = 0;
        double aiTotalPnlPct = 0.0;
        double baselineTotalPnlPct = 0.0;
        List<TradeResult> trades = new ArrayList<>();
        boolean truncated = false;
        Set<Instant> processedSignalTimes = new HashSet<>();

        // Walk forward bar by bar starting at index 200
        for (int i = 200; i < bars.size(); i++) {
            // Build subseries [0..i] (no look-ahead)
            List<OhlcBarDTO> subseries = bars.subList(0, i + 1);
            BarSeries series = buildBarSeries(subseries);

            // Detect DTB signal on subseries
            Optional<DtbDetector.DtbSignal> signalOpt = dtbDetector.detectLatest(series, 100);

            if (!signalOpt.isPresent()) {
                continue;
            }

            DtbDetector.DtbSignal dtbSignal = signalOpt.get();
            Instant signalTime = dtbSignal.signalTime();
            OhlcBarDTO lastBar = subseries.get(subseries.size() - 1);
            Instant lastBarTime = Instant.ofEpochSecond(lastBar.getTime());

            // Check freshness: signal should be 0-2 bars old
            if (signalTime.getEpochSecond() > lastBar.getTime() + 7200) { // >2 hours old
                continue;
            }

            totalSignals++;
            log.info("Signal #{}: {} at {} (bar index {})", totalSignals, dtbSignal.direction(), signalTime, i);

            // Dedupe: skip if we've already processed this signal time
            if (!processedSignalTimes.add(signalTime)) {
                log.debug("Skipping duplicate signal at {}", signalTime);
                continue;
            }

            // Look up or generate AI levels for this signal date
            LocalDate signalDate = signalTime.atZone(ZoneId.of("Asia/Kolkata")).toLocalDate();
            Optional<AiLevels> levelsOpt = aiLevelsRepository
                    .findBySymbolAndTimeframeAndGeneratedForDate(symbol, "OneHour", signalDate);

            AiLevels levels;
            if (levelsOpt.isEmpty()) {
                if (aiCallCount >= maxAiCalls) {
                    String errMsg = String.format("Exceeded max AI calls (%d), halting. Processed %d of %d bars.",
                            maxAiCalls, i, bars.size());
                    log.warn(errMsg);
                    truncated = true;
                    break;
                }

                // Call AI levels agent with asOf = signalTime
                log.info("Generating AI levels for {} on {}", symbol, signalDate);
                levels = levelsAgentService.runForSymbol(symbol, userId, Optional.of(signalTime));
                aiCallCount++;
            } else {
                levels = levelsOpt.get();
                log.debug("Reused cached AI levels for {} on {}", symbol, signalDate);
            }

            // Build PatternSignal from DTB detection
            PatternSignal patternSignal = PatternSignal.builder()
                    .symbol(symbol)
                    .patternType("DTB")
                    .patternSource("DtbDetector")
                    .direction(dtbSignal.direction())
                    .timeframe("OneHour")
                    .suggestedEntry(dtbSignal.neckline())
                    .suggestedSl(dtbSignal.neckline() + dtbSignal.atr() * 2) // rough SL
                    .suggestedTarget(dtbSignal.measuredMoveTarget())
                    .signalTime(signalTime)
                    .signalRef("DTB_" + i)
                    .build();

            // Call AI pattern agent
            log.info("Calling AI pattern agent for signal {}", totalSignals);
            com.dtech.aitrader.data.AgentDecision agentDecision = patternAgentService.decide(patternSignal, userId);
            aiCallCount++;

            // Record the decision
            String verdict = agentDecision.getVerdict();
            if ("TRADE".equals(verdict)) {
                aiTrades++;

                // Walk forward from i+1 to find exit (SL/target/timeout)
                Double exitPrice = findExit(bars, i, agentDecision, dtbSignal);
                Double pnlPct = computePnl(agentDecision.getEntry().doubleValue(), exitPrice);

                trades.add(new TradeResult(
                        signalTime,
                        "DTB",
                        agentDecision.getDirection(),
                        "TRADE",
                        agentDecision.getEntry().doubleValue(),
                        agentDecision.getSl().doubleValue(),
                        agentDecision.getTarget().doubleValue(),
                        exitPrice,
                        pnlPct,
                        agentDecision.getReasoning()
                ));

                aiTotalPnlPct += pnlPct;
                log.info("Trade #{}: {} {} exit={} PnL={}%",
                        aiTrades, agentDecision.getDirection(), patternSignal.getSymbol(), exitPrice, pnlPct);
            } else {
                aiNoTrades++;
                log.info("NO_TRADE decision: {}", agentDecision.getReasoning());
            }
        }

        log.info("Walk-forward complete: signals={}, trades={}, no_trades={}, AI_PnL={}%, baseline_PnL={}%, truncated={}",
                totalSignals, aiTrades, aiNoTrades, aiTotalPnlPct, baselineTotalPnlPct, truncated);

        return new WalkForwardResult(
                totalSignals,
                aiTrades,
                aiNoTrades,
                aiTotalPnlPct,
                baselineTotalPnlPct,
                trades,
                truncated
        );
    }

    private Double findExit(List<OhlcBarDTO> bars, int entryBarIdx,
                            com.dtech.aitrader.data.AgentDecision decision,
                            DtbDetector.DtbSignal dtbSignal) {
        double entry = decision.getEntry().doubleValue();
        double sl = decision.getSl().doubleValue();
        double target = decision.getTarget().doubleValue();

        // Walk forward up to 80 bars
        for (int i = entryBarIdx + 1; i < Math.min(entryBarIdx + 81, bars.size()); i++) {
            OhlcBarDTO bar = bars.get(i);

            if ("LONG".equals(decision.getDirection())) {
                // Hit target
                if (bar.getHigh() >= target) {
                    return target;
                }
                // Hit SL
                if (bar.getLow() <= sl) {
                    return sl;
                }
            } else {
                // SHORT
                // Hit target
                if (bar.getLow() <= target) {
                    return target;
                }
                // Hit SL
                if (bar.getHigh() >= sl) {
                    return sl;
                }
            }
        }

        // Timeout: return last bar's close
        return (double) bars.get(Math.min(entryBarIdx + 80, bars.size() - 1)).getClose();
    }

    private Double computePnl(Double entry, Double exit) {
        if (entry == null || exit == null || entry == 0.0) {
            return 0.0;
        }
        return (exit - entry) / entry * 100.0;
    }

    private BarSeries buildBarSeries(List<OhlcBarDTO> bars) {
        BarSeries series = new BaseBarSeriesBuilder().withName("walk-forward").build();
        for (OhlcBarDTO bar : bars) {
            Instant timestamp = Instant.ofEpochSecond(bar.getTime());
            Bar taBar = com.dtech.kitecon.strategy.dataloader.BarsLoader.getBar(
                    bar.getOpen(), bar.getHigh(), bar.getLow(), bar.getClose(),
                    bar.getVolume(), timestamp);
            series.addBar(taBar);
        }
        return series;
    }
}
