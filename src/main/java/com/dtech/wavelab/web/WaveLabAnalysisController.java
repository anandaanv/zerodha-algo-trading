package com.dtech.wavelab.web;

import com.dtech.wavelab.entity.WlOverlay;
import com.dtech.wavelab.service.WaveLabAnalysisService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/wave-lab/analyze")
@CrossOrigin
public class WaveLabAnalysisController {

    private final WaveLabAnalysisService analysisService;

    @PostMapping
    public ResponseEntity<?> analyze(
            @RequestParam String symbol,
            @RequestParam String timeframe) {
        try {
            List<WlOverlay> overlays = analysisService.analyze(symbol, timeframe);
            return ResponseEntity.ok(overlays);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<?> analyzeAll(
            @RequestParam Long watchlistId,
            @RequestParam String timeframe) {
        WaveLabAnalysisService.BatchResult result = analysisService.analyzeAll(watchlistId, timeframe);
        return ResponseEntity.ok(result);
    }
}
