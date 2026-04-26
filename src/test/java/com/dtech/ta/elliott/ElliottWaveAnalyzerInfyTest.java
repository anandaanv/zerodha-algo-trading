package com.dtech.ta.elliott;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.KiteconApplication;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.MarketStructureService;
import com.dtech.kitecon.service.copilot.dto.MarketStructureData;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Disabled;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.ta4j.core.BarSeries;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for the Elliott Wave engine on real INFY data.
 *
 * Validates that Layers 4–7 (Enrich → PatternDetect → WaveCount → ScenarioBuilder)
 * produce a coherent output and that toPromptSummary() is ready for AI consumption.
 *
 * Prerequisites:
 * - MySQL running with INFY instrument + daily/1h/15min candles
 *
 * Run: ./gradlew test --tests "*.ElliottWaveAnalyzerInfyTest"
 */
@Slf4j
@Disabled("Requires real MySQL — deferred to T6.2")
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
class ElliottWaveAnalyzerInfyTest {

    private static final String SYMBOL = "INFY";

    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private ZigZagService zigzagService;
    @Autowired private MarketStructureService marketStructureService;
    @Autowired private ElliottWaveAnalyzer elliottWaveAnalyzer;

    private Instrument instrument;

    @BeforeEach
    void setUp() {
        instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(SYMBOL, new String[]{"NSE"});
        assertNotNull(instrument, "INFY instrument must exist in the DB");
    }

    @Test
    void elliottWaveEngine_infy_producesCoherentOutput() {
        // ── Collect data for three timeframes ────────────────────────────────────
        List<String> tfOrder = List.of("daily", "1h", "15min");

        Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
        Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
        Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();

        Map<String, Interval> tfToInterval = Map.of(
                "daily",  Interval.Day,
                "1h",     Interval.OneHour,
                "15min",  Interval.FifteenMinute
        );

        for (String tf : tfOrder) {
            Interval interval = tfToInterval.get(tf);
            try {
                List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(SYMBOL, instrument, interval);
                if (pivots.isEmpty()) {
                    log.warn("[Test] No pivots for {} {} — skipping TF", SYMBOL, tf);
                    continue;
                }
                MarketStructureData msd = marketStructureService.analyse(pivots, tf);
                BarSeries series = zigzagService.getBarSeries(SYMBOL, instrument, interval);

                pivotsByTf.put(tf, pivots);
                structureByTf.put(tf, msd);
                seriesByTf.put(tf, series);

                log.info("[Test] {} {}: {} pivots, {} bars, trend={}",
                        SYMBOL, tf, pivots.size(), series.getBarCount(), msd.getTrendDirection());
            } catch (Exception e) {
                log.warn("[Test] Failed to load {} {}: {}", SYMBOL, tf, e.getMessage());
            }
        }

        assertFalse(pivotsByTf.isEmpty(), "Must have data for at least one timeframe");

        // Use the finest TF that has data as primary
        String primaryTf = tfOrder.stream()
                .filter(pivotsByTf::containsKey)
                .reduce((a, b) -> b)  // last = finest
                .orElseThrow();
        List<String> availableTfs = tfOrder.stream().filter(pivotsByTf::containsKey).toList();

        log.info("[Test] Running ElliottWaveAnalyzer — primaryTf={}, TFs={}", primaryTf, availableTfs);

        // ── Run the engine ───────────────────────────────────────────────────────
        ElliottWaveAnalysis analysis = elliottWaveAnalyzer.analyze(
                pivotsByTf, structureByTf, seriesByTf, availableTfs, primaryTf);

        assertNotNull(analysis, "Analysis must not be null");

        // ── Log everything ───────────────────────────────────────────────────────
        log.info("\n========== ELLIOTT WAVE ANALYSIS: {} ==========", SYMBOL);
        log.info("Primary TF: {}", analysis.getPrimaryTimeframe());
        log.info("Timeframes: {}", analysis.getTimeframes());
        log.info("Wave counts generated: {}", analysis.getWaveCounts().size());
        log.info("Patterns detected: {}", analysis.getAllPatterns().size());
        log.info("Scenarios built: {}", analysis.getScenarios().size());

        // Log enriched pivot summary per TF
        for (Map.Entry<String, List<EnrichedPivot>> entry : analysis.getEnrichedPivots().entrySet()) {
            String tf = entry.getKey();
            List<EnrichedPivot> pivots = entry.getValue();
            log.info("\n--- Enriched Pivots [{}] ({} total, showing last 10) ---", tf, pivots.size());
            int start = Math.max(0, pivots.size() - 10);
            for (int i = start; i < pivots.size(); i++) {
                EnrichedPivot p = pivots.get(i);
                log.info("  [{}] {} price={} ewo={} rsi={} adx={} label={}",
                        i,
                        p.isHigh() ? "HIGH" : "LOW",
                        String.format("%.2f", p.getPrice()),
                        String.format("%.2f", p.getEwo()),
                        String.format("%.1f", p.getRsi()),
                        String.format("%.1f", p.getAdx()),
                        p.getStructureLabel());
            }
        }

        // Log patterns
        if (!analysis.getAllPatterns().isEmpty()) {
            log.info("\n--- Detected Patterns ({}) ---", analysis.getAllPatterns().size());
            for (PatternMatch pm : analysis.getAllPatterns()) {
                log.info("  {} [{}] tf={} confidence={:.0f}% target={} invalidation={}",
                        pm.getType(), pm.getStatus(), pm.getTimeframe(),
                        pm.getConfidence() * 100,
                        pm.getTarget(), pm.getInvalidation());
                if (pm.getWaitingFor() != null) {
                    log.info("    → Waiting for: {}", pm.getWaitingFor());
                }
            }
        } else {
            log.info("\n--- No patterns detected ---");
        }

        // Log wave counts
        if (!analysis.getWaveCounts().isEmpty()) {
            log.info("\n--- Wave Counts ({}) ---", analysis.getWaveCounts().size());
            for (WaveCount wc : analysis.getWaveCounts()) {
                log.info("  {} [{}] score={} fib={} indicator={} crossTf={} alternation={}",
                        wc.getWaveType(), wc.getPrimaryTimeframe(),
                        wc.totalScore(), wc.getFibonacciScore(),
                        wc.getIndicatorScore(), wc.getCrossTfScore(),
                        wc.getAlternationScore());
                log.info("    current: {} | W3ext={}%",
                        wc.getCurrentPositionDescription(),
                        wc.getWave3ExtensionPct() != null ? String.format("%.1f", wc.getWave3ExtensionPct()) : "n/a");
                if (!wc.getRuleViolations().isEmpty()) {
                    log.info("    violations: {}", wc.getRuleViolations());
                }
            }
        } else {
            log.info("\n--- No wave counts generated ---");
        }

        // Log scenarios
        if (!analysis.getScenarios().isEmpty()) {
            log.info("\n--- Scenarios ({}) ---", analysis.getScenarios().size());
            for (WaveScenario scenario : analysis.getScenarios()) {
                log.info("  [{}] {} — hypotheses: {}",
                        scenario.getId(), scenario.getDirectionLabel(), scenario.getHypotheses().size());
                log.info("    invalidation: {}", scenario.getScenarioInvalidation());
                for (WaveHypothesis hyp : scenario.getHypotheses()) {
                        log.info("    Hypothesis #{}: score={} target={} invalidation={}",
                            hyp.getId(), hyp.getTotalScore(),
                            hyp.getPrimaryTarget(), hyp.getInvalidationLevel());
                    log.info("      position: {}", hyp.getCurrentPositionDescription());
                    log.info("      supporting: {}", hyp.getSupportingEvidence() != null ? hyp.getSupportingEvidence().size() : 0);
                    log.info("      contradicting: {}", hyp.getContradictingEvidence() != null ? hyp.getContradictingEvidence().size() : 0);
                }
                if (!scenario.getDecisionPoints().isEmpty()) {
                    log.info("    Decision points: {}", scenario.getDecisionPoints().size());
                }
            }
        } else {
            log.info("\n--- No scenarios built ---");
        }

        // ── Print the AI-ready prompt summary ────────────────────────────────────
        String promptSummary = analysis.toPromptSummary();
        assertNotNull(promptSummary, "toPromptSummary() must not be null");
        assertFalse(promptSummary.isBlank(), "toPromptSummary() must not be blank");

        log.info("\n========== AI PROMPT SUMMARY ({} chars) ==========\n{}", promptSummary.length(), promptSummary);

        // ── Basic structural assertions ───────────────────────────────────────────
        assertFalse(analysis.getEnrichedPivots().isEmpty(), "Must have enriched pivots");

        // Each enriched pivot should have non-zero indicator values (sanity check)
        analysis.getEnrichedPivots().values().forEach(pivots -> {
            if (!pivots.isEmpty()) {
                EnrichedPivot last = pivots.get(pivots.size() - 1);
                assertTrue(last.getPrice() > 0, "Enriched pivot price must be positive");
                // RSI and ADX should be computable (may be 0 if series too short, but not negative)
                assertTrue(last.getRsi() >= 0, "RSI must be >= 0");
                assertTrue(last.getAdx() >= 0, "ADX must be >= 0");
            }
        });

        // If scenarios exist, top scenario should be accessible
        if (!analysis.getScenarios().isEmpty()) {
            WaveScenario top = analysis.topScenario();
            assertNotNull(top, "topScenario() must return a result");
            assertNotNull(top.getDirection(), "Top scenario must have a direction");
            log.info("\n[Test] TOP SCENARIO: {} — {}", top.getDirection(), top.getDirectionLabel());
        }

        // Prompt summary should mention INFY-relevant content
        assertTrue(promptSummary.length() > 100,
                "Prompt summary must have substantial content");

        log.info("\n========== TEST COMPLETE ==========");
    }
}
