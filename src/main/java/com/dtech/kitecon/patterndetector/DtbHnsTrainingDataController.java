package com.dtech.kitecon.patterndetector;

import com.dtech.kitecon.repository.InstrumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST controller for DTB+HNS training data extraction.
 *
 * Endpoints:
 * - POST /api/training/dtbhns/extract — extract for symbols (ALL or comma-separated list)
 * - POST /api/training/dtbhns/extract-test — extract for test symbols (POLYCAB, DMART, etc.)
 */
@RestController
@RequestMapping("/api/training/dtbhns")
@Slf4j
@RequiredArgsConstructor
public class DtbHnsTrainingDataController {

    private final DtbHnsTrainingDataService trainingDataService;
    private final InstrumentRepository instrumentRepository;

    /**
     * Extract training data for specified symbols and date range.
     *
     * @param from ISO-8601 timestamp (e.g., 2015-01-01T00:00:00Z)
     * @param to   ISO-8601 timestamp (e.g., 2022-01-01T00:00:00Z)
     * @param symbols "ALL" for all FnO symbols, or comma-separated list (e.g., "POLYCAB,DMART,RELIANCE")
     * @param output file path for CSV output (e.g., /tmp/dtbhns_train.csv)
     * @return ExtractResult with stats
     */
    @PostMapping("/extract")
    public DtbHnsTrainingDataService.ExtractResult extract(
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam(value = "symbols", defaultValue = "ALL") String symbols,
            @RequestParam("output") String output) {

        try {
            Instant fromInst = Instant.parse(from);
            Instant toInst = Instant.parse(to);

            List<String> symbolList;
            if ("ALL".equalsIgnoreCase(symbols)) {
                symbolList = instrumentRepository.findDistinctFutureUnderlyingNamesWithNseEquity();
                log.info("[DtbHnsController] Expanding symbols=ALL to {} symbols", symbolList.size());
            } else {
                symbolList = Arrays.asList(symbols.split(","));
            }

            log.info("[DtbHnsController] Starting extraction: from={} to={} symbols={} output={}",
                    from, to, symbolList.size(), output);

            return trainingDataService.extractMultiSymbol(
                    symbolList, fromInst, toInst, Paths.get(output));

        } catch (Exception e) {
            log.error("[DtbHnsController] Extraction failed: {}", e.getMessage(), e);
            throw new RuntimeException("Extraction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Extract training data for a small test set.
     * Useful for quick validation before running full extraction.
     *
     * @param symbols comma-separated list (e.g., POLYCAB,DMART)
     * @param from ISO-8601 timestamp
     * @param to ISO-8601 timestamp
     * @param output file path for CSV output
     * @return ExtractResult with stats
     */
    @PostMapping("/extract-test")
    public DtbHnsTrainingDataService.ExtractResult extractTest(
            @RequestParam("symbols") String symbols,
            @RequestParam("from") String from,
            @RequestParam("to") String to,
            @RequestParam("output") String output) {

        try {
            Instant fromInst = Instant.parse(from);
            Instant toInst = Instant.parse(to);

            List<String> symbolList = Arrays.asList(symbols.split(","));

            log.info("[DtbHnsController] Starting test extraction: symbols={} from={} to={} output={}",
                    symbolList.size(), from, to, output);

            return trainingDataService.extractMultiSymbol(
                    symbolList, fromInst, toInst, Paths.get(output));

        } catch (Exception e) {
            log.error("[DtbHnsController] Test extraction failed: {}", e.getMessage(), e);
            throw new RuntimeException("Test extraction failed: " + e.getMessage(), e);
        }
    }

    /**
     * Health check endpoint.
     */
    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "DtbHnsTrainingDataService");
    }
}
