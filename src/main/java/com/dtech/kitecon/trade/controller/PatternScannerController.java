package com.dtech.kitecon.trade.controller;

import com.dtech.kitecon.patternscanner.PatternComboScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/scanner")
@RequiredArgsConstructor
@Slf4j
public class PatternScannerController {

    private final PatternComboScannerService patternComboScannerService;

    @PostMapping("/scan-now")
    public ResponseEntity<Map<String, Integer>> scanNow() {
        try {
            int created = patternComboScannerService.scanAndCreateSignals();
            Map<String, Integer> result = new HashMap<>();
            result.put("created", created);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Error during manual scan: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
