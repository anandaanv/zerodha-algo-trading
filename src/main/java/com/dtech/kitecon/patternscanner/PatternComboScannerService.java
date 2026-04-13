package com.dtech.kitecon.patternscanner;

import com.dtech.algo.runner.candle.KiteTickerService;
import com.dtech.algo.series.Interval;
import com.dtech.kitecon.trade.entity.TradeSignal;
import com.dtech.kitecon.trade.enums.TradeDirection;
import com.dtech.kitecon.trade.enums.TradeStatus;
import com.dtech.kitecon.trade.repository.TradeSignalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
@RequiredArgsConstructor
public class PatternComboScannerService {

    private final PatternScanService patternScanService;
    private final TradeSignalRepository tradeSignalRepository;
    private final TradeFilterClient tradeFilterClient;
    private final KiteTickerService kiteTickerService;

    @Value("${trade.monitor.entry.validity.hours:4}")
    private int entryValidityHours;

    @Value("${trade.filter.threshold:0.82}")
    private double filterThreshold;

    @Value("${trade.scanner.watching.tf:1h}")
    private String watchingTfStr;

    @Value("${trade.scanner.confirm.tf:15m}")
    private String confirmTfStr;

    private final AtomicBoolean scanRunning = new AtomicBoolean(false);

    @Scheduled(cron = "0 0/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledScan() {
        if (!kiteTickerService.isMarketLive()) {
            log.info("[PatternScanner] Skipping scan — no recent ticks (trading holiday or pre-market)");
            return;
        }
        if (!scanRunning.compareAndSet(false, true)) {
            log.warn("[PatternScanner] Previous scan still running — skipping this tick");
            return;
        }
        try {
            int count = scanAndCreateSignals();
            log.info("[PatternScanner] Scheduled scan completed: {} new signals created", count);
        } finally {
            scanRunning.set(false);
        }
    }

    public int scanAndCreateSignals() {
        Interval watchingTf = parseInterval(watchingTfStr);
        Interval confirmTf = parseInterval(confirmTfStr);

        int totalCreated = 0;

        for (String symbol : patternScanService.getFnoSymbols()) {
            try {
                PatternScanResultDto result = patternScanService.scan(symbol, watchingTf, confirmTf);
                List<PatternDto> patterns = result.getPatterns();

                if (patterns != null) {
                    for (PatternDto pattern : patterns) {
                        SignalCreationResult cr = createSignalForPattern(symbol, pattern, result.getWatchingTf());
                        if (cr.outcome() == SignalOutcome.CREATED) {
                            totalCreated++;
                        }
                    }
                }

            } catch (Exception e) {
                log.error("Error scanning symbol {}: {}", symbol, e.getMessage());
            }
        }

        return totalCreated;
    }

    private Interval parseInterval(String tf) {
        return switch (tf.toLowerCase()) {
            case "1d", "day", "d" -> Interval.Day;
            case "1h", "hour", "h" -> Interval.OneHour;
            case "15m", "15min" -> Interval.FifteenMinute;
            case "5m", "5min" -> Interval.FiveMinute;
            default -> Interval.OneHour;
        };
    }

    enum SignalOutcome { CREATED, DUPLICATE, ML_FILTERED }

    record SignalCreationResult(Long signalId, SignalOutcome outcome) {
        static SignalCreationResult created(Long id) { return new SignalCreationResult(id, SignalOutcome.CREATED); }
        static SignalCreationResult duplicate()       { return new SignalCreationResult(null, SignalOutcome.DUPLICATE); }
        static SignalCreationResult mlFiltered()      { return new SignalCreationResult(null, SignalOutcome.ML_FILTERED); }
    }

    SignalCreationResult createSignalForPattern(String symbol, PatternDto pattern, String timeframe) {
        List<TradeSignal> openSignals = tradeSignalRepository.findBySymbolAndStatusIn(
                symbol, List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE));

        for (TradeSignal existing : openSignals) {
            if (existing.getPatternType() != null && existing.getPatternType().equals(pattern.getPatternType())) {
                if (existing.getNeckline() != null) {
                    double keyLevel = pattern.getKeyLevel();
                    double necklineDiff = Math.abs(existing.getNeckline().doubleValue() - keyLevel) / keyLevel;
                    if (necklineDiff < 0.005) {
                        log.debug("Duplicate pattern detected for {} {}: skipping", symbol, pattern.getPatternType());
                        return SignalCreationResult.duplicate();
                    }
                }
            }
        }

        BigDecimal keyLevel = BigDecimal.valueOf(pattern.getKeyLevel());
        BigDecimal target = BigDecimal.valueOf(pattern.getTarget());
        double atr = pattern.getAtr();

        BigDecimal stopLoss;
        if (pattern.isBullish()) {
            stopLoss = keyLevel.subtract(BigDecimal.valueOf(2.0 * atr));
        } else {
            stopLoss = keyLevel.add(BigDecimal.valueOf(2.0 * atr));
        }

        BigDecimal rrRatio;
        try {
            BigDecimal targetMinusEntry = target.subtract(keyLevel);
            BigDecimal entryMinusSL = keyLevel.subtract(stopLoss).abs();
            if (entryMinusSL.compareTo(BigDecimal.ZERO) > 0) {
                rrRatio = targetMinusEntry.divide(entryMinusSL, 2, RoundingMode.HALF_UP);
            } else {
                rrRatio = BigDecimal.ZERO;
            }
        } catch (Exception e) {
            rrRatio = BigDecimal.ZERO;
        }

        TradeSignal signal = TradeSignal.builder()
                .symbol(symbol)
                .exchange("NSE")
                .instrumentType("EQ")
                .direction(pattern.isBullish() ? TradeDirection.LONG : TradeDirection.SHORT)
                .patternType(pattern.getPatternType())
                .confirmationType("WATCHING")
                .entryPrice(keyLevel)
                .stopLoss(stopLoss)
                .target(target)
                .neckline(keyLevel)
                .patternHeight(BigDecimal.valueOf(pattern.getPatternHeight()))
                .stochRsiK(BigDecimal.valueOf(pattern.getRsiAtP1()))
                .timeframe(timeframe)
                .signalTime(Instant.now())
                .entryValidUntil(Instant.now().plus(entryValidityHours, ChronoUnit.HOURS))
                .status(TradeStatus.WATCHING_ENTRY)
                .lotSize(1)
                .rrRatio(rrRatio)
                .notes("Auto-scan: rsiP1=" + pattern.getRsiAtP1() + " rsiP2=" + pattern.getRsiAtP2())
                .build();

        // ML filter — score the pattern and store for reference, but do NOT gate here.
        // Filtering happens at entry time (TradeEntryHandler) when the pattern has fully formed.
        double prob = tradeFilterClient.score(
                pattern, pattern.getPatternType(),
                pattern.getDailyRsi(), pattern.getDailyAdx(), pattern.getDailyAdxEma(),
                pattern.getAdxWatching(), pattern.getAdxWatchingEma(),
                pattern.getAdxConfirm(), pattern.getAdxConfirmEma(),
                pattern.getMacdWatching(), pattern.getMacdSignalWatching(),
                pattern.getBbWidthWatching(), pattern.getBbPctBWatching(),
                pattern.getMacdDaily(), pattern.getMacdSignalDaily(),
                pattern.getBbWidthDaily(), pattern.getBbPctBDaily()
        );
        signal.setMlScore(BigDecimal.valueOf(prob).setScale(4, RoundingMode.HALF_UP));

        signal = tradeSignalRepository.save(signal);

        log.info("[Scanner] New signal: {} {} {} keyLevel={}",
                signal.getId(), symbol, pattern.getPatternType(), pattern.getKeyLevel());

        return SignalCreationResult.created(signal.getId());
    }
}
