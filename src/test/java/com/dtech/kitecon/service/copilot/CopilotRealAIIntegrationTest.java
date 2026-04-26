package com.dtech.kitecon.service.copilot;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.KiteconApplication;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.data.copilot.CopilotObservation;
import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.repository.copilot.CopilotObservationRepository;
import com.dtech.kitecon.repository.copilot.CopilotSkillRepository;
import com.dtech.kitecon.service.copilot.dto.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that makes REAL OpenAI API calls to validate
 * scan and reasoning prompts produce correctly structured responses.
 *
 * Uses:
 * - Real MySQL database with INFY candle data
 * - Real OpenAI API key stored for user anand1711@gmail.com (userId=4)
 * - Real CopilotAIService (NOT mocked)
 *
 * This test costs real API credits. Run manually:
 *   ./gradlew test --tests "*.CopilotRealAIIntegrationTest"
 */
@Slf4j
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
@Disabled("Requires real MySQL + OpenAI API — will be replaced by Testcontainers in T6.2")
class CopilotRealAIIntegrationTest {

    private static final String SYMBOL = "INFY";
    private static final String TIMEFRAME = "1h";
    /** User anand1711@gmail.com — has OpenAI key stored in copilot_user_openai_credential */
    private static final Long USER_ID = 4L;

    @Autowired private InstrumentRepository instrumentRepository;
    @Autowired private ZigZagService zigzagService;
    @Autowired private MarketStructureService marketStructureService;
    @Autowired private CopilotSkillService skillService;
    @Autowired private CopilotInvestigationService investigationService;
    @Autowired private CopilotObservationService observationService;
    @Autowired private CopilotOrchestratorService orchestratorService;
    @Autowired private CopilotHypothesisService hypothesisService;
    @Autowired private AIResponseParser responseParser;
    @Autowired private CopilotObservationRepository observationRepository;
    @Autowired private CopilotSkillRepository skillRepository;
    @Autowired private CopilotAIService aiService; // REAL — not mocked

    private Instrument instrument;
    private CopilotSkill doubleBottomSkill;

    @BeforeEach
    void setUp() {
        instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(SYMBOL, new String[]{"NSE"});
        assertNotNull(instrument, "INFY instrument must exist in the database.");

        doubleBottomSkill = ensureDoubleBottomSkill();
        skillService.seedReasoningSkillsForUser(USER_ID);
    }

    // ─── Test 1: Scan Phase — real AI call with double_bottom skill ──────────────

    @Test
    void scanPhase_realAI_producesValidObservation() throws Exception {
        log.info("========================================================================");
        log.info("=== REAL AI TEST: Phase 1 SCAN — double_bottom skill on INFY 1h ===");
        log.info("========================================================================");

        // ─── Prepare real market data ─────────────────────────────────────────────
        List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(SYMBOL, instrument, Interval.OneHour);
        assertFalse(pivots.isEmpty(), "ZigZag pivots must exist for INFY 1h");
        log.info("Loaded {} zigzag pivots for {} {}", pivots.size(), SYMBOL, TIMEFRAME);

        // Log last 10 pivots
        int start = Math.max(0, pivots.size() - 10);
        for (ZigZagPoint p : pivots.subList(start, pivots.size())) {
            log.info("  {} {:.2f} @ {} retr={:.1f}%",
                    p.isHigh() ? "HIGH" : " LOW", p.getValue(), p.getTimestamp(),
                    p.getRetracementPct() != null ? p.getRetracementPct() : 0.0);
        }

        MarketStructureData msd = marketStructureService.analyse(pivots, TIMEFRAME);
        log.info("Market structure trend: {}", msd.getTrendDirection());

        // ─── Create investigation ─────────────────────────────────────────────────
        CopilotInvestigation investigation = investigationService.startInvestigation(
                9001L, USER_ID, SYMBOL, TIMEFRAME, 60, "REAL_AI_TEST");

        String zigzagSummary = buildZigzagSummary(pivots);

        investigationService.updateData(investigation.getId(),
                zigzagSummary, msd.toPromptSummary(), null);
        investigation = investigationService.getOrThrow(investigation.getId());

        // ─── Build scan prompt ────────────────────────────────────────────────────
        String scanSkillPrompt = skillService.buildScanSkillPrompt(doubleBottomSkill);
        String fullScanPrompt = orchestratorService.buildScanPrompt(scanSkillPrompt, investigation);

        log.info("Scan prompt ({} chars):", fullScanPrompt.length());
        log.info("--- SCAN PROMPT START ---");
        log.info("{}", fullScanPrompt);
        log.info("--- SCAN PROMPT END ---");

        // ─── Make REAL AI call ────────────────────────────────────────────────────
        log.info(">>> Calling OpenAI (scan phase)...");
        String rawScanResponse = aiService.call(USER_ID, fullScanPrompt,
                "Analyse the chart data provided. Return your response as the specified JSON.");
        log.info("<<< AI scan response ({} chars):", rawScanResponse.length());
        log.info("{}", rawScanResponse);

        // ─── Parse and validate response ──────────────────────────────────────────
        AIResponse parsed = responseParser.parse(rawScanResponse);
        assertNotNull(parsed, "AI response must parse successfully");
        assertInstanceOf(ObservationResponse.class, parsed,
                "Scan response must be OBSERVATION type. Raw: " + rawScanResponse);

        ObservationResponse obs = (ObservationResponse) parsed;

        // Structural validation — these fields must always be present
        assertNotNull(obs.getPatternDetected(), "patternDetected must be set");
        assertNotNull(obs.getReasoning(), "reasoning must be set");
        log.info("Pattern detected: {}", obs.getPatternDetected());
        log.info("Pattern type: {}", obs.getPatternType());
        log.info("Confidence: {}", obs.getConfidence());
        log.info("Stage: {}", obs.getStage());
        log.info("Reasoning: {}", obs.getReasoning());

        if (obs.getPatternDetected()) {
            assertNotNull(obs.getPatternType(), "patternType required when detected");
            assertNotNull(obs.getConfidence(), "confidence required when detected");
            assertTrue(List.of("HIGH", "MEDIUM", "LOW").contains(obs.getConfidence()),
                    "confidence must be HIGH, MEDIUM, or LOW, got: " + obs.getConfidence());

            // Drawing points — should exist for detected patterns
            if (obs.getDrawingPoints() != null && !obs.getDrawingPoints().isEmpty()) {
                log.info("Drawing points ({}):", obs.getDrawingPoints().size());
                for (var dp : obs.getDrawingPoints()) {
                    log.info("  {} — time={} price={}", dp.getLabel(), dp.getTime(), dp.getPrice());
                    assertNotNull(dp.getTime(), "drawingPoint time must be set");
                    assertNotNull(dp.getPrice(), "drawingPoint price must be set");
                    assertTrue(dp.getPrice() > 0, "price must be positive");
                }
            }

            if (obs.getDrawingType() != null) {
                List<String> validDrawingTypes = List.of(
                        "triangle_pattern", "elliott_impulse_wave", "elliott_correction",
                        "elliott_triangle_wave", "head_and_shoulders", "parallel_channel",
                        "fib_retracement", "xabcd_pattern", "trend_line");
                assertTrue(validDrawingTypes.contains(obs.getDrawingType()),
                        "drawingType must be valid TradingView tool, got: " + obs.getDrawingType());
                log.info("Drawing type: {}", obs.getDrawingType());
            }

            if (obs.getKeyLevels() != null) {
                log.info("Key levels ({}):", obs.getKeyLevels().size());
                for (var kl : obs.getKeyLevels()) {
                    log.info("  {} @ {}", kl.getLabel(), kl.getPrice());
                }
            }

            if (obs.getContradictions() != null && !obs.getContradictions().isEmpty()) {
                log.info("Contradictions: {}", obs.getContradictions());
            }

            log.info("SCAN RESULT: {} detected with {} confidence at stage {}",
                    obs.getPatternType(), obs.getConfidence(), obs.getStage());
        } else {
            log.info("SCAN RESULT: No double bottom detected. Reasoning: {}", obs.getReasoning());
        }

        // ─── Persist observation ──────────────────────────────────────────────────
        CopilotObservation savedObs = observationService.createFromScan(
                investigation.getId(), "double_bottom", obs, rawScanResponse);
        investigationService.updatePhase(investigation.getId(), "SCANNED");

        assertNotNull(savedObs.getId());
        log.info("Observation #{} persisted", savedObs.getId());
    }

    // ─── Test 2: Full pipeline — scan then reason with real AI ───────────────────

    @Test
    void fullPipeline_realAI_scanThenReason() throws Exception {
        log.info("========================================================================");
        log.info("=== REAL AI TEST: Full Pipeline — Scan + Reason on INFY 1h ===");
        log.info("========================================================================");

        // ─── Prepare market data ──────────────────────────────────────────────────
        List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(SYMBOL, instrument, Interval.OneHour);
        assertFalse(pivots.isEmpty());
        MarketStructureData msd = marketStructureService.analyse(pivots, TIMEFRAME);

        CopilotInvestigation investigation = investigationService.startInvestigation(
                9002L, USER_ID, SYMBOL, TIMEFRAME, 60, "REAL_AI_FULL");

        String zigzagSummary = buildZigzagSummary(pivots);

        investigationService.updateData(investigation.getId(),
                zigzagSummary, msd.toPromptSummary(), null);
        investigation = investigationService.getOrThrow(investigation.getId());

        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 1: SCAN
        // ═══════════════════════════════════════════════════════════════════════════
        log.info("=== PHASE 1: SCAN ===");

        String scanSkillPrompt = skillService.buildScanSkillPrompt(doubleBottomSkill);
        String fullScanPrompt = orchestratorService.buildScanPrompt(scanSkillPrompt, investigation);

        log.info(">>> Calling OpenAI (scan)...");
        String rawScanResponse = aiService.call(USER_ID, fullScanPrompt,
                "Analyse the chart data provided. Return your response as the specified JSON.");
        log.info("<<< Scan response:\n{}", rawScanResponse);

        AIResponse parsedScan = responseParser.parse(rawScanResponse);
        assertInstanceOf(ObservationResponse.class, parsedScan,
                "Must be OBSERVATION. Raw: " + rawScanResponse);
        ObservationResponse obs = (ObservationResponse) parsedScan;

        // Persist
        CopilotObservation savedObs = observationService.createFromScan(
                investigation.getId(), "double_bottom", obs, rawScanResponse);
        investigationService.recordSkillInvoked(investigation.getId(), "double_bottom", rawScanResponse);
        investigationService.updatePhase(investigation.getId(), "SCANNED");

        log.info("Scan complete: pattern={} detected={} confidence={}",
                obs.getPatternType(), obs.getPatternDetected(), obs.getConfidence());

        // Build observations summary for Phase 2
        String obsSummary = observationService.buildObservationsSummary(investigation.getId());
        log.info("Observations summary:\n{}", obsSummary);

        // ═══════════════════════════════════════════════════════════════════════════
        // PHASE 2: REASON
        // ═══════════════════════════════════════════════════════════════════════════
        log.info("=== PHASE 2: REASON ===");

        List<CopilotSkill> reasoningSkills = skillService.getReasoningSkillsForUser(USER_ID);
        assertFalse(reasoningSkills.isEmpty(), "Reasoning skills must exist");
        log.info("Available reasoning skills: {}",
                reasoningSkills.stream().map(CopilotSkill::getSkillKey).toList());

        // Use the first reasoning skill (confluence_checker)
        CopilotSkill reasonSkill = reasoningSkills.get(0);
        String reasonSkillPrompt = skillService.buildSkillPrompt(reasonSkill);
        investigation = investigationService.getOrThrow(investigation.getId());
        String fullReasonPrompt = orchestratorService.buildReasoningPrompt(
                reasonSkillPrompt, investigation, obsSummary, null, null, null);

        log.info("Reasoning prompt ({} chars):", fullReasonPrompt.length());
        log.info("--- REASON PROMPT START ---");
        log.info("{}", fullReasonPrompt);
        log.info("--- REASON PROMPT END ---");

        log.info(">>> Calling OpenAI (reason)...");
        String rawReasonResponse = aiService.call(USER_ID, fullReasonPrompt,
                "Analyse the observations and produce your assessment as the specified JSON.");
        log.info("<<< Reason response:\n{}", rawReasonResponse);

        // ─── Parse and validate reasoning response ────────────────────────────────
        AIResponse parsedReason = responseParser.parse(rawReasonResponse);
        assertNotNull(parsedReason, "Reason response must parse");

        log.info("Reason response type: {}", parsedReason.getType());

        if (parsedReason instanceof FindingResponse finding) {
            log.info("=== FINDING PRODUCED ===");
            log.info("Hypothesis: {}", finding.getHypothesisLabel());
            log.info("Description: {}", finding.getHypothesisDescription());
            log.info("Wave context: {}", finding.getWaveContext());
            log.info("Pattern: {}", finding.getPattern());
            log.info("Stage: {}", finding.getCurrentStage());
            log.info("Confidence layers: {}", finding.getConfidenceLayers());

            if (finding.getAnticipatoryEntry() != null) {
                log.info("Anticipatory entry:");
                log.info("  Zone: {}", finding.getAnticipatoryEntry().getEntryZone());
                log.info("  SL: {}", finding.getAnticipatoryEntry().getSl());
                log.info("  TP: {}", finding.getAnticipatoryEntry().getTp());
                log.info("  Conditions: {}", finding.getAnticipatoryEntry().getConditionsNeeded());
                log.info("  Trigger: {}", finding.getAnticipatoryEntry().getTriggerDescription());
            }

            if (finding.getConfirmationEntry() != null) {
                log.info("Confirmation entry:");
                log.info("  Zone: {}", finding.getConfirmationEntry().getEntryZone());
                log.info("  SL: {}", finding.getConfirmationEntry().getSl());
                log.info("  TP: {}", finding.getConfirmationEntry().getTp());
                log.info("  Conditions: {}", finding.getConfirmationEntry().getConditionsNeeded());
            }

            if (finding.getInvalidationConditions() != null) {
                log.info("Invalidation conditions: {}", finding.getInvalidationConditions());
            }

            assertNotNull(finding.getHypothesisLabel(), "Finding must have a label");
            assertNotNull(finding.getReasoning(), "Finding must have reasoning");

            // Create hypothesis from the AI finding
            var hypothesis = hypothesisService.createFromFinding(investigation.getId(), finding);
            investigationService.updatePhase(investigation.getId(), "REASONED");

            assertNotNull(hypothesis.getId());
            log.info("Hypothesis #{} created: '{}' [{}]",
                    hypothesis.getId(), hypothesis.getLabel(), hypothesis.getState());

        } else if (parsedReason instanceof NeedsExpertResponse needsExpert) {
            log.info("=== NEEDS EXPERT ===");
            log.info("Question: {}", needsExpert.getQuestionText());
            log.info("Options: {}", needsExpert.getOptions());
            log.info("Reasoning: {}", needsExpert.getReasoning());
            // This is a valid outcome — AI is uncertain and asks for human input

        } else if (parsedReason instanceof InvalidatedResponse invalidated) {
            log.info("=== INVALIDATED ===");
            log.info("What was invalidated: {}", invalidated.getHypothesisLabel());
            log.info("Reasoning: {}", invalidated.getReasoning());
            // Also valid — AI found the observation doesn't hold up

        } else {
            log.warn("Unexpected response type: {}", parsedReason.getClass().getSimpleName());
            log.warn("Raw: {}", rawReasonResponse);
        }

        // ─── Final state ──────────────────────────────────────────────────────────
        investigation = investigationService.getOrThrow(investigation.getId());
        List<CopilotObservation> allObs = observationService.getObservationsForInvestigation(investigation.getId());

        log.info("========================================================================");
        log.info("=== FULL PIPELINE COMPLETE ===");
        log.info("  Investigation #{} phase={}", investigation.getId(), investigation.getPhase());
        log.info("  Observations: {} (detected: {})", allObs.size(),
                allObs.stream().filter(CopilotObservation::getPatternDetected).count());
        var allHypotheses = hypothesisService.getAllHypotheses(investigation.getId());
        log.info("  Hypotheses: {}", allHypotheses.size());
        for (var h : allHypotheses) {
            log.info("    #{} '{}' [{}] pattern={}", h.getId(), h.getLabel(), h.getState(), h.getPattern());
        }
        log.info("========================================================================");
    }

    // ─── Test 3: HEROMOTOCO double top scan with real AI ────────────────────────

    @Test
    void scanPhase_realAI_heroMotoco_doubleTop() throws Exception {
        log.info("========================================================================");
        log.info("=== REAL AI TEST: HEROMOTOCO Double Top Scan (1h) ===");
        log.info("========================================================================");

        // ─── Look up HEROMOTOCO instrument ────────────────────────────────────────
        Instrument heroInstrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                "HEROMOTOCO", new String[]{"NSE"});
        assertNotNull(heroInstrument, "HEROMOTOCO instrument must exist in the database.");

        // ─── Compute zigzag pivots (will auto-detect from candle data) ────────────
        List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(
                "HEROMOTOCO", heroInstrument, Interval.OneHour);
        assertFalse(pivots.isEmpty(), "ZigZag pivots must exist for HEROMOTOCO 1h");
        log.info("Loaded {} zigzag pivots for HEROMOTOCO 1h", pivots.size());

        // Log last 15 pivots for visibility
        int logStart = Math.max(0, pivots.size() - 15);
        for (int i = logStart; i < pivots.size(); i++) {
            ZigZagPoint p = pivots.get(i);
            log.info("  [{}] {} {:.2f} @ {} retr={:.1f}%",
                    i, p.isHigh() ? "HIGH" : " LOW", p.getValue(), p.getTimestamp(),
                    p.getRetracementPct() != null ? p.getRetracementPct() : 0.0);
        }

        MarketStructureData msd = marketStructureService.analyse(pivots, TIMEFRAME);
        log.info("Market structure trend: {}", msd.getTrendDirection());

        // ─── Create double_top skill ──────────────────────────────────────────────
        CopilotSkill doubleTopSkill = ensureDoubleTopSkill();

        // ─── Create investigation ─────────────────────────────────────────────────
        CopilotInvestigation investigation = investigationService.startInvestigation(
                9003L, USER_ID, "HEROMOTOCO", TIMEFRAME, 60, "REAL_AI_HERO_DT");

        String zigzagSummary = buildZigzagSummaryForSymbol(pivots, TIMEFRAME);

        investigationService.updateData(investigation.getId(),
                zigzagSummary, msd.toPromptSummary(), null);
        investigation = investigationService.getOrThrow(investigation.getId());

        // ─── Build scan prompt ────────────────────────────────────────────────────
        String scanSkillPrompt = skillService.buildScanSkillPrompt(doubleTopSkill);
        String fullScanPrompt = orchestratorService.buildScanPrompt(scanSkillPrompt, investigation);

        log.info("Scan prompt ({} chars)", fullScanPrompt.length());

        // ─── Make REAL AI call ────────────────────────────────────────────────────
        log.info(">>> Calling OpenAI (HEROMOTOCO double top scan)...");
        String rawScanResponse = aiService.call(USER_ID, fullScanPrompt,
                "Analyse the chart data provided. Return your response as the specified JSON.");
        log.info("<<< AI scan response ({} chars):\n{}", rawScanResponse.length(), rawScanResponse);

        // ─── Parse and validate response ──────────────────────────────────────────
        AIResponse parsed = responseParser.parse(rawScanResponse);
        assertNotNull(parsed, "AI response must parse successfully");
        assertInstanceOf(ObservationResponse.class, parsed,
                "Scan response must be OBSERVATION type. Raw: " + rawScanResponse);

        ObservationResponse obs = (ObservationResponse) parsed;
        assertNotNull(obs.getPatternDetected(), "patternDetected must be set");

        log.info("========================================================================");
        log.info("=== HEROMOTOCO DOUBLE TOP SCAN RESULT ===");
        log.info("  Pattern detected: {}", obs.getPatternDetected());
        log.info("  Pattern type: {}", obs.getPatternType());
        log.info("  Confidence: {}", obs.getConfidence());
        log.info("  Stage: {}", obs.getStage());
        log.info("  Structural details: {}", obs.getStructuralDetails());

        if (obs.getKeyLevels() != null) {
            for (var kl : obs.getKeyLevels()) {
                log.info("  Key level: {} @ {}", kl.getLabel(), kl.getPrice());
            }
        }

        if (obs.getDrawingPoints() != null) {
            log.info("  Drawing points ({}):", obs.getDrawingPoints().size());
            for (var dp : obs.getDrawingPoints()) {
                log.info("    {} — time={} price={}", dp.getLabel(), dp.getTime(), dp.getPrice());
            }
        }

        if (obs.getContradictions() != null && !obs.getContradictions().isEmpty()) {
            log.info("  Contradictions: {}", obs.getContradictions());
        }

        log.info("  Reasoning: {}", obs.getReasoning());
        log.info("========================================================================");

        // Persist observation
        CopilotObservation savedObs = observationService.createFromScan(
                investigation.getId(), "double_top", obs, rawScanResponse);
        investigationService.updatePhase(investigation.getId(), "SCANNED");
        log.info("Observation #{} persisted", savedObs.getId());
    }

    // ─── Test 4: Seed all chart pattern skills ────────────────────────────────────

    @Test
    void seedAllChartPatternSkills() {
        log.info("========================================================================");
        log.info("=== SEEDING ALL CHART PATTERN SKILLS for userId={} ===", USER_ID);
        log.info("========================================================================");

        int seeded = skillService.seedChartPatternSkillsForUser(USER_ID);
        log.info("Seeded {} new chart pattern skills", seeded);

        // Verify all 18 patterns exist
        List<CopilotSkill> scanSkills = skillService.getScanSkillsForUser(USER_ID);
        List<CopilotSkill> patternSkills = scanSkills.stream()
                .filter(s -> "PATTERN".equals(s.getCategory()))
                .toList();

        log.info("Total PATTERN skills for user: {}", patternSkills.size());
        for (CopilotSkill skill : patternSkills) {
            log.info("  [{}] {} — {}", skill.getSkillKey(), skill.getName(), skill.getDescription());
        }

        assertTrue(patternSkills.size() >= 18,
                "Expected at least 18 pattern skills, got: " + patternSkills.size());
    }

    // ─── Test 5: Seed all wave skills ───────────────────────────────────────────

    @Test
    void seedAllWaveSkills() {
        log.info("========================================================================");
        log.info("=== SEEDING ALL WAVE SKILLS for userId={} ===", USER_ID);
        log.info("========================================================================");

        int seeded = skillService.seedWaveSkillsForUser(USER_ID);
        log.info("Seeded {} new wave skills", seeded);

        // Verify all 15 wave skills exist
        List<CopilotSkill> scanSkills = skillService.getScanSkillsForUser(USER_ID);
        List<CopilotSkill> waveSkills = scanSkills.stream()
                .filter(s -> "WAVE".equals(s.getCategory()))
                .toList();

        log.info("Total WAVE skills for user: {}", waveSkills.size());
        for (CopilotSkill skill : waveSkills) {
            log.info("  [{}] {} — {}", skill.getSkillKey(), skill.getName(), skill.getDescription());
        }

        // 15 new wave skills + existing wave_4 + triangle = at least 15 new ones
        assertTrue(waveSkills.size() >= 15,
                "Expected at least 15 wave skills, got: " + waveSkills.size());
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private String buildZigzagSummaryForSymbol(List<ZigZagPoint> pivots, String timeframe) {
        StringBuilder sb = new StringBuilder("=== " + timeframe + " ===\n");
        sb.append(String.format("  Total pivots: %d (showing last %d)%n",
                pivots.size(), Math.min(30, pivots.size())));
        int start = Math.max(0, pivots.size() - 30);
        for (int i = start; i < pivots.size(); i++) {
            ZigZagPoint p = pivots.get(i);
            sb.append(String.format("  [%d] %s %.2f @ %s retr=%.1f%% ext=%.1f%%%n",
                    i, p.isHigh() ? "HIGH" : "LOW", p.getValue(), p.getTimestamp(),
                    p.getRetracementPct() != null ? p.getRetracementPct() : 0.0,
                    p.getExtensionPct() != null ? p.getExtensionPct() : 0.0));
        }
        return sb.toString();
    }

    private CopilotSkill ensureDoubleTopSkill() {
        Optional<CopilotSkill> existing = skillRepository.findByUserIdAndSkillKey(USER_ID, "double_top");
        if (existing.isPresent()) return existing.get();

        return skillService.saveSkill(CopilotSkill.builder()
                .userId(USER_ID)
                .name("Double Top Skill")
                .description("Classic double top (M pattern) reversal detection — bearish")
                .category("PATTERN")
                .skillKey("double_top")
                .isSystemSeed(false)
                .identificationRules("""
                    A double top is a bearish reversal pattern forming an "M" shape.
                    Required: Two distinct highs at approximately the same price level (within 1-2% tolerance).
                    Between the two highs, there must be a pullback forming the "neckline" low.
                    The second high should fail at or slightly below the first high level.
                    Volume typically decreases on the second high compared to the first — showing weakening buying.
                    Pattern is confirmed when price breaks below the neckline with conviction.
                    Also detect recently completed double tops where the neckline has already broken —
                    the pattern is confirmed and a short trade may be running.
                    """)
                .stageDetection("""
                    Stage detection for double top:
                    - FIRST_HIGH: Initial rally has completed, first pullback starting
                    - NECKLINE_PULLBACK: Price has pulled back from first high, forming interim low (neckline)
                    - SECOND_HIGH: Price rallying toward first high area — watching for resistance failure
                    - BREAKDOWN_PENDING: Second high has failed, price declining toward neckline
                    - CONFIRMED: Price has broken below neckline — pattern confirmed, trade active
                    - BREAKDOWN_ACTIVE: Price well below neckline, measured move target in play
                    """)
                .entryRules("""
                    ANTICIPATORY ENTRY (at second high / resistance failure):
                    - Entry zone: at or near the first high price level (short entry)
                    - SL: above the higher of the two highs (1-2% buffer)
                    - TP: neckline - (high - neckline) = measured move target
                    - Requires: lower TF bearish reversal candle + volume fade

                    CONFIRMATION ENTRY (neckline breakdown + retest):
                    - Entry: on pullback to neckline after breakdown (short entry)
                    - SL: above neckline
                    - TP: measured move from breakdown point
                    - Requires: neckline holds as resistance on retest
                    """)
                .indicatorRules("""
                    RSI: Bearish divergence between first high and second high (higher price or equal price, lower RSI) is strong confirmation.
                    MACD: Should show decreasing momentum on the second high.
                    Volume: Should be lower on second high than first — shows buying exhaustion.
                    Volume expansion on neckline break confirms the pattern.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Second high breaks significantly above the first high (>2%) with conviction
                    - Price fails to break the neckline and instead rallies to new highs
                    - Neckline breakdown is immediately bought back (failed breakdown)
                    - RSI makes higher highs on both attempts (no divergence — trend continuation)
                    """)
                .ambiguityQuestions("""
                    Q1: Are the two highs close enough in price to qualify as a double top?
                    Q2: Is the timeframe between the two highs reasonable (not too wide or too narrow)?
                    Q3: Could this be a continuation pattern (flag/pennant) rather than a reversal?
                    """)
                .crossVerificationRules("""
                    CV-1: Higher timeframe must show the rally is mature (not early in a new uptrend).
                    CV-2: Volume profile should show distribution at the double top zone.
                    CV-3: Key resistance levels from higher TF should align with the top zone.
                    """)
                .build());
    }

    private String buildZigzagSummary(List<ZigZagPoint> pivots) {
        StringBuilder zigzagSummary = new StringBuilder("=== " + TIMEFRAME + " ===\n");
        zigzagSummary.append(String.format("  Total pivots: %d (showing last %d)%n",
                pivots.size(), Math.min(30, pivots.size())));
        int start = Math.max(0, pivots.size() - 30);
        for (int i = start; i < pivots.size(); i++) {
            ZigZagPoint p = pivots.get(i);
            zigzagSummary.append(String.format("  [%d] %s %.2f @ %s retr=%.1f%% ext=%.1f%%%n",
                    i, p.isHigh() ? "HIGH" : "LOW", p.getValue(), p.getTimestamp(),
                    p.getRetracementPct() != null ? p.getRetracementPct() : 0.0,
                    p.getExtensionPct() != null ? p.getExtensionPct() : 0.0));
        }
        return zigzagSummary.toString();
    }

    private CopilotSkill ensureDoubleBottomSkill() {
        Optional<CopilotSkill> existing = skillRepository.findByUserIdAndSkillKey(USER_ID, "double_bottom");
        if (existing.isPresent()) return existing.get();

        return skillService.saveSkill(CopilotSkill.builder()
                .userId(USER_ID)
                .name("Double Bottom Skill")
                .description("Classic double bottom (W pattern) reversal detection")
                .category("PATTERN")
                .skillKey("double_bottom")
                .isSystemSeed(false)
                .identificationRules("""
                    A double bottom is a bullish reversal pattern forming a "W" shape.
                    Required: Two distinct lows at approximately the same price level (within 1-2% tolerance).
                    Between the two lows, there must be a rally forming the "neckline" high.
                    The second low should hold at or slightly above the first low level.
                    Volume typically decreases on the second low compared to the first.
                    Pattern is confirmed when price breaks above the neckline with conviction.
                    """)
                .stageDetection("""
                    Stage detection for double bottom:
                    - FIRST_LOW: Initial decline has completed, first bounce starting
                    - NECKLINE_RALLY: Price has bounced from first low, forming interim high
                    - SECOND_LOW: Price declining toward first low area — watching for support hold
                    - BREAKOUT_PENDING: Second low has held, price rallying toward neckline
                    - CONFIRMED: Price has broken above neckline — pattern confirmed
                    """)
                .entryRules("""
                    ANTICIPATORY ENTRY (at second low / support hold):
                    - Entry zone: at or near the first low price level
                    - SL: below the lower of the two lows (1-2% buffer)
                    - TP: neckline + (neckline - low) = measured move target
                    - Requires: lower TF reversal candle + volume pick-up

                    CONFIRMATION ENTRY (neckline breakout + retest):
                    - Entry: on pullback to neckline after breakout
                    - SL: below neckline
                    - TP: measured move from breakout point
                    - Requires: neckline holds as support on retest
                    """)
                .indicatorRules("""
                    RSI: Bullish divergence between first low and second low (lower price, higher RSI) is strong confirmation.
                    MACD: Should show decreasing momentum on the second low.
                    Volume: Should be lower on second low than first — shows selling exhaustion.
                    Volume expansion on neckline break confirms the pattern.
                    """)
                .invalidationRules("""
                    INVALIDATED if:
                    - Second low breaks significantly below the first low (>2%)
                    - Price fails to rally from the second low zone within a reasonable timeframe
                    - Neckline breakout is immediately sold back into (failed breakout)
                    - RSI makes lower lows on both attempts (no divergence — trend continuation)
                    """)
                .ambiguityQuestions("""
                    Q1: Are the two lows close enough in price to qualify as a double bottom?
                    Q2: Is the timeframe between the two lows reasonable (not too wide or too narrow)?
                    Q3: Could this be a continuation pattern rather than a reversal?
                    """)
                .crossVerificationRules("""
                    CV-1: Higher timeframe must show the decline is mature (not early in a new downtrend).
                    CV-2: Volume profile should support accumulation at the double bottom zone.
                    CV-3: Key support levels from higher TF should align with the bottom zone.
                    """)
                .build());
    }
}
