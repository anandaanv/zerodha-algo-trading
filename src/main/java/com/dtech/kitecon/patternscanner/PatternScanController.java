package com.dtech.kitecon.patternscanner;

import com.dtech.algo.series.Interval;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/pattern-scan")
@RequiredArgsConstructor
@Slf4j
public class PatternScanController {

    private final PatternScanService patternScanService;
    private final com.dtech.kitecon.backtest.PatternComboBacktestService patternComboBacktestService;
    private final com.dtech.kitecon.elliott.ImpulseTrainingDataService impulseTrainingDataService;

    @GetMapping("/nifty50")
    public ResponseEntity<List<String>> getNifty50() {
        return ResponseEntity.ok(patternScanService.getNifty50());
    }

    @GetMapping("/fno-symbols")
    public ResponseEntity<java.util.List<String>> getFnoSymbols() {
        return ResponseEntity.ok(patternScanService.getFnoSymbols());
    }

    @GetMapping("/{symbol}")
    public ResponseEntity<?> scan(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String watchingTf,
            @RequestParam(defaultValue = "15m") String confirmTf) {
        try {
            Interval wTf = parseInterval(watchingTf);
            Interval cTf = parseInterval(confirmTf);
            PatternScanResultDto result = patternScanService.scan(symbol, wTf, cTf);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Pattern scan failed for {}: {}", symbol, e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/backtest/tlb/{symbol}")
    public ResponseEntity<?> backtestTlb(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String tf) {
        try {
            Interval interval = parseInterval(tf);
            String csvPath = "/tmp/tlb_backtest_" + symbol + "_" + tf + ".csv";
            String result = patternComboBacktestService.backtestTrendlineBreakout(symbol, interval, csvPath);
            return ResponseEntity.ok(java.util.Map.of("summary", result, "csvPath", csvPath));
        } catch (Exception e) {
            log.error("TLB backtest failed for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/backtest/tlb-nifty50")
    public ResponseEntity<?> backtestTlbNifty50(
            @RequestParam(defaultValue = "1h") String tf) {
        try {
            Interval interval = parseInterval(tf);
            String csvPath = "/tmp/tlb_backtest_nifty50_" + tf + ".csv";
            String result = patternComboBacktestService.backtestTrendlineBreakoutMultiple(
                    patternScanService.getNifty50(), interval, csvPath);
            return ResponseEntity.ok(java.util.Map.of("summary", result, "csvPath", csvPath));
        } catch (Exception e) {
            log.error("TLB backtest failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/backtest/tlb-fno")
    public ResponseEntity<?> backtestTlbFno(
            @RequestParam(defaultValue = "1h") String tf) {
        try {
            Interval interval = parseInterval(tf);
            String csvPath = "/tmp/tlb_backtest_fno_" + tf + ".csv";
            String result = patternComboBacktestService.backtestTrendlineBreakoutMultiple(
                    patternScanService.getFnoSymbols(), interval, csvPath);
            return ResponseEntity.ok(java.util.Map.of("summary", result, "csvPath", csvPath));
        } catch (Exception e) {
            log.error("TLB FnO backtest failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/impulse-train/{symbol}")
    public ResponseEntity<?> generateImpulseData(
            @PathVariable String symbol,
            @RequestParam(defaultValue = "1h") String tf,
            @RequestParam(defaultValue = "pivot-scan") String strategy) {
        try {
            com.dtech.algo.series.Interval interval = parseInterval(tf);
            String result = impulseTrainingDataService.generateForSymbol(symbol, interval, strategy);
            return ResponseEntity.ok(java.util.Map.of("summary", result));
        } catch (Exception e) {
            log.error("Impulse data gen failed for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/impulse-train-batch")
    public ResponseEntity<?> generateImpulseBatch(
            @RequestParam(defaultValue = "1h") String tf,
            @RequestParam(defaultValue = "RELIANCE,TCS,INFY,ADANIENT,POWERGRID,RECLTD,CONCOR") String symbols,
            @RequestParam(defaultValue = "pivot-scan") String strategy) {
        try {
            com.dtech.algo.series.Interval interval = parseInterval(tf);
            List<String> symbolList = java.util.Arrays.asList(symbols.split(","));
            String result = impulseTrainingDataService.generateForSymbols(symbolList, interval, strategy);
            return ResponseEntity.ok(java.util.Map.of("report", result));
        } catch (Exception e) {
            log.error("Impulse batch failed: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
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
}
