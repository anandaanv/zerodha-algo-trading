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

    @GetMapping("/nifty50")
    public ResponseEntity<List<String>> getNifty50() {
        return ResponseEntity.ok(patternScanService.getNifty50());
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
