package com.dtech.kitecon.web.copilot;

import com.dtech.kitecon.analysis.AnalysisProcessService;
import com.dtech.kitecon.analysis.dto.ProcessAnalysisRequest;
import com.dtech.kitecon.analysis.dto.ProcessAnalysisResponse;
import com.dtech.ta.elliott.ElliottWaveAnalysis;
import com.dtech.ta.elliott.ElliottWaveAnalyzer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@Slf4j
public class AnalysisProcessController {

    private final AnalysisProcessService processService;
    private final ElliottWaveAnalyzer elliottWaveAnalyzer;

    @PostMapping("/process")
    public ResponseEntity<?> processAnalysis(@RequestBody ProcessAnalysisRequest request) {
        try {
            log.info(
                    "[AnalysisProcess] Processing for symbol={}, price={}, tf={}",
                    request.getSymbol(),
                    request.getCurrentPrice(),
                    request.getEntryTimeframe()
            );

            // Validate that raw engine output is provided
            if (request.getRawEngineOutput() == null || request.getRawEngineOutput().isBlank()) {
                return ResponseEntity.badRequest().body(
                        Map.of("error", "raw_engine_output is required. Run /api/analysis/trigger first to get the engine output, then pass it here.")
                );
            }

            // Create a minimal stub ElliottWaveAnalysis for processing
            ElliottWaveAnalysis stubAnalysis = ElliottWaveAnalysis.builder()
                    .primaryTimeframe(request.getEntryTimeframe())
                    .timeframes(List.of("1w", "1d", "1h", "15m"))
                    .scenarios(List.of())
                    .allPatterns(List.of())
                    .waveCounts(List.of())
                    .nestedCorrectiveContexts(List.of())
                    .build();

            // Process the analysis
            ProcessAnalysisResponse response = processService.process(
                    stubAnalysis,
                    request.getSymbol(),
                    request.getCurrentPrice(),
                    request.getEntryTimeframe()
            );

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("[AnalysisProcess] Error processing analysis for symbol={}", request.getSymbol(), e);
            return ResponseEntity.status(500).body(
                    Map.of("error", "Processing failed: " + e.getMessage())
            );
        }
    }

    /**
     * Internal helper method that can be called with a real ElliottWaveAnalysis object.
     * Useful when called from other controllers (e.g., CopilotAnalysisController).
     */
    public ProcessAnalysisResponse processWithAnalysis(
            ElliottWaveAnalysis analysis,
            String symbol,
            double currentPrice,
            String entryTf) {
        return processService.process(analysis, symbol, currentPrice, entryTf);
    }
}
