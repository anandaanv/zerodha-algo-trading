package com.dtech.kitecon.patternscanner;

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

@Service
@Slf4j
@RequiredArgsConstructor
public class PatternComboScannerService {

    private final PatternScanService patternScanService;
    private final TradeSignalRepository tradeSignalRepository;

    @Value("${trade.monitor.entry.validity.hours:4}")
    private int entryValidityHours;

    @Value("${trade.scanner.watching.tf:1h}")
    private String watchingTfStr;

    @Value("${trade.scanner.confirm.tf:15m}")
    private String confirmTfStr;

    @Scheduled(cron = "0 0/15 9-15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledScan() {
        int count = scanAndCreateSignals();
        log.info("[PatternScanner] Scheduled scan completed: {} new signals created", count);
    }

    public int scanAndCreateSignals() {
        Interval watchingTf = parseInterval(watchingTfStr);
        Interval confirmTf = parseInterval(confirmTfStr);

        int totalCreated = 0;

        for (String symbol : patternScanService.getNifty50()) {
            try {
                PatternScanResultDto result = patternScanService.scan(symbol, watchingTf, confirmTf);
                List<PatternDto> patterns = result.getPatterns();

                if (patterns != null) {
                    for (PatternDto pattern : patterns) {
                        if (createSignalIfNew(symbol, pattern, result.getWatchingTf())) {
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

    private boolean createSignalIfNew(String symbol, PatternDto pattern, String timeframe) {
        List<TradeSignal> openSignals = tradeSignalRepository.findBySymbolAndStatusIn(
                symbol, List.of(TradeStatus.WATCHING_ENTRY, TradeStatus.ENTRY_PENDING, TradeStatus.ACTIVE));

        for (TradeSignal existing : openSignals) {
            if (existing.getPatternType() != null && existing.getPatternType().equals(pattern.getPatternType())) {
                if (existing.getNeckline() != null) {
                    double keyLevel = pattern.getKeyLevel();
                    double necklineDiff = Math.abs(existing.getNeckline().doubleValue() - keyLevel) / keyLevel;
                    if (necklineDiff < 0.005) {
                        log.debug("Duplicate pattern detected for {} {}: skipping", symbol, pattern.getPatternType());
                        return false;
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

        signal = tradeSignalRepository.save(signal);

        log.info("[Scanner] New signal: {} {} {} keyLevel={}",
                signal.getId(), symbol, pattern.getPatternType(), pattern.getKeyLevel());

        return true;
    }
}
