package com.dtech.kitecon.patternscanner;

import com.dtech.algo.series.Interval;
import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.enums.TradingSegment;
import com.dtech.kitecon.trade.repository.SegmentConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class PatternScreenerService {

    private final PatternScreenerRepository screenerRepository;
    private final PatternScreenerRunRepository runRepository;
    private final PatternScreenerRunResultRepository resultRepository;
    private final PatternScanService patternScanService;
    private final PatternComboScannerService patternComboScannerService;
    private final SegmentConfigRepository segmentConfigRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public List<PatternScreener> listScreeners(Long userId) {
        return screenerRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public PatternScreener createScreener(Long userId, PatternScreenerRequest req) {
        PatternScreener s = PatternScreener.builder()
                .userId(userId)
                .name(req.getName())
                .segments(req.getSegments())
                .watchingTf(req.getWatchingTf())
                .confirmTf(req.getConfirmTf())
                .scheduleCron(req.getScheduleCron())
                .enabled(req.isEnabled())
                .build();
        return screenerRepository.save(s);
    }

    public PatternScreener updateScreener(Long userId, Long id, PatternScreenerRequest req) {
        PatternScreener s = screenerRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Not found: " + id));
        s.setName(req.getName());
        s.setSegments(req.getSegments());
        s.setWatchingTf(req.getWatchingTf());
        s.setConfirmTf(req.getConfirmTf());
        s.setScheduleCron(req.getScheduleCron());
        s.setEnabled(req.isEnabled());
        return screenerRepository.save(s);
    }

    public void deleteScreener(Long userId, Long id) {
        screenerRepository.deleteById(id);
    }

    @Async("scanExecutor")
    public void triggerAsync(Long screenerId) {
        PatternScreener screener = screenerRepository.findById(screenerId).orElse(null);
        if (screener == null) return;
        runScreener(screener);
    }

    public PatternScreenerRun runScreener(PatternScreener screener) {
        if (runRepository.existsByScreenerIdAndStatus(screener.getId(), "RUNNING")) {
            log.warn("[PatternScreener] Screener {} '{}' is already running — skipping overlap", screener.getId(), screener.getName());
            return runRepository.findTop10ByScreenerIdOrderByStartedAtDesc(screener.getId())
                    .stream().findFirst().orElse(null);
        }
        List<TradingSegment> segs = Arrays.stream(screener.getSegments().split(","))
                .map(String::trim).map(String::toUpperCase)
                .map(TradingSegment::valueOf)
                .collect(Collectors.toList());

        List<String> symbols = segmentConfigRepository.findBySegmentInAndEnabledTrue(segs)
                .stream().map(SegmentConfig::getSymbol).distinct().collect(Collectors.toList());

        PatternScreenerRun run = PatternScreenerRun.builder()
                .screenerId(screener.getId())
                .status("RUNNING")
                .totalSymbols(symbols.size())
                .startedAt(Instant.now())
                .build();
        run = runRepository.save(run);

        Interval watchingTf = parseInterval(screener.getWatchingTf());
        Interval confirmTf  = parseInterval(screener.getConfirmTf());

        int signalsCreated = 0, duplicatesSkipped = 0;
        StringBuilder errors = new StringBuilder();

        for (String symbol : symbols) {
            long t0 = System.currentTimeMillis();
            String status = "NO_PATTERN";
            String patternsJson = "[]";
            Long signalId = null;
            String errorMessage = null;

            try {
                PatternScanResultDto result = patternScanService.scan(symbol, watchingTf, confirmTf);
                List<PatternDto> patterns = result.getPatterns();

                if (patterns != null && !patterns.isEmpty()) {
                    patternsJson = objectMapper.writeValueAsString(patterns);
                    status = "PATTERN_FOUND";

                    for (PatternDto pattern : patterns) {
                        PatternComboScannerService.SignalCreationResult cr =
                                patternComboScannerService.createSignalForPattern(symbol, pattern, result.getWatchingTf());
                        if (cr.outcome() == PatternComboScannerService.SignalOutcome.CREATED) {
                            signalId = cr.signalId();
                            signalsCreated++;
                            status = "SIGNAL_CREATED";
                        } else if (cr.outcome() == PatternComboScannerService.SignalOutcome.DUPLICATE) {
                            duplicatesSkipped++;
                            if (!"SIGNAL_CREATED".equals(status)) status = "DUPLICATE";
                        } else if (cr.outcome() == PatternComboScannerService.SignalOutcome.ML_FILTERED) {
                            if (!"SIGNAL_CREATED".equals(status) && !"DUPLICATE".equals(status)) status = "FILTERED";
                        }
                    }
                }
            } catch (Exception e) {
                status = "ERROR";
                errorMessage = e.getMessage();
                errors.append(symbol).append(": ").append(e.getMessage()).append("\n");
                log.error("[PatternScreener] Error scanning {}: {}", symbol, e.getMessage());
            }

            PatternScreenerRunResult res = PatternScreenerRunResult.builder()
                    .runId(run.getId())
                    .screenerId(screener.getId())
                    .symbol(symbol)
                    .status(status)
                    .patternsFound(patternsJson)
                    .signalId(signalId)
                    .errorMessage(errorMessage)
                    .processingMs(System.currentTimeMillis() - t0)
                    .scannedAt(Instant.now())
                    .build();
            resultRepository.save(res);

            run.setProcessedSymbols(run.getProcessedSymbols() + 1);
            run.setSignalsCreated(signalsCreated);
            run.setDuplicatesSkipped(duplicatesSkipped);
            runRepository.save(run);
        }

        run.setStatus("COMPLETED");
        run.setCompletedAt(Instant.now());
        if (errors.length() > 0) run.setErrorSummary(errors.toString());

        screener.setLastRunAt(Instant.now());
        screenerRepository.save(screener);

        return runRepository.save(run);
    }

    private Interval parseInterval(String tf) {
        return switch (tf.toLowerCase()) {
            case "1d", "day", "d" -> Interval.Day;
            case "1h", "hour", "h" -> Interval.OneHour;
            case "15m", "15min"    -> Interval.FifteenMinute;
            case "5m",  "5min"     -> Interval.FiveMinute;
            default                -> Interval.OneHour;
        };
    }
}
