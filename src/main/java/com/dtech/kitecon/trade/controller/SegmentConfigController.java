package com.dtech.kitecon.trade.controller;

import com.dtech.kitecon.patternscanner.PatternScanService;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.entity.SegmentConfig;
import com.dtech.kitecon.trade.enums.TradingSegment;
import com.dtech.kitecon.trade.repository.SegmentConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/segment-config")
@RequiredArgsConstructor
@Slf4j
public class SegmentConfigController {

    private final SegmentConfigRepository segmentConfigRepository;
    private final PatternScanService patternScanService;
    private final InstrumentRepository instrumentRepository;

    @GetMapping("/")
    public ResponseEntity<List<SegmentConfig>> getAll() {
        List<SegmentConfig> configs = segmentConfigRepository.findAllByOrderBySymbolAscSegmentAsc();
        return ResponseEntity.ok(configs);
    }

    @PostMapping("/")
    public ResponseEntity<SegmentConfig> create(@RequestBody SegmentConfig config) {
        config.setId(null);
        SegmentConfig saved = segmentConfigRepository.save(config);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<SegmentConfig> update(@PathVariable Long id, @RequestBody SegmentConfig config) {
        Optional<SegmentConfig> existing = segmentConfigRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        SegmentConfig entity = existing.get();
        if (config.getSymbol() != null) {
            entity.setSymbol(config.getSymbol());
        }
        if (config.getSegment() != null) {
            entity.setSegment(config.getSegment());
        }
        entity.setEnabled(config.isEnabled());
        if (config.getCapitalPct() != null) {
            entity.setCapitalPct(config.getCapitalPct());
        }
        if (config.getProvider() != null) {
            entity.setProvider(config.getProvider());
        }
        if (config.getNotes() != null) {
            entity.setNotes(config.getNotes());
        }

        SegmentConfig saved = segmentConfigRepository.save(entity);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (!segmentConfigRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        segmentConfigRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/all")
    public ResponseEntity<Void> deleteAll() {
        segmentConfigRepository.deleteAll();
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/seed")
    public ResponseEntity<Map<String, Integer>> seed(
            @RequestParam String list,
            @RequestParam String segment) {
        int seeded = 0;

        List<String> symbols;
        if ("FNO".equalsIgnoreCase(list)) {
            symbols = patternScanService.getFnoSymbols(); // already excludes index futures (NIFTY, BANKNIFTY etc.)
        } else {
            symbols = patternScanService.getNifty50();
        }

        TradingSegment seg = TradingSegment.valueOf(segment.toUpperCase());

        for (String symbol : symbols) {
            Optional<SegmentConfig> existing = segmentConfigRepository.findBySymbolAndSegment(symbol, seg);
            if (existing.isEmpty()) {
                BigDecimal capitalPct = seg == TradingSegment.OPT ? BigDecimal.valueOf(1.0) : BigDecimal.valueOf(2.0);
                SegmentConfig config = SegmentConfig.builder()
                        .symbol(symbol)
                        .segment(seg)
                        .enabled(true)
                        .capitalPct(capitalPct)
                        .provider("KITE")
                        .build();
                segmentConfigRepository.save(config);
                seeded++;
            }
        }

        Map<String, Integer> result = new HashMap<>();
        result.put("seeded", seeded);
        return ResponseEntity.ok(result);
    }
}
