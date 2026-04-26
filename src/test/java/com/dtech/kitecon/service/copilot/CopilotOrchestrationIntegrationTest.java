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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Integration test for the two-phase Co-Pilot orchestration.
 *
 * Uses the REAL MySQL database (with actual INFY candle data and zigzag pivots),
 * but MOCKS the AI service to return controlled responses.
 *
 * Validates:
 * 1. Phase 1 (SCAN): skill prompts are built with real market data → AI returns OBSERVATION
 *    → observations are persisted with correct drawingPoints/drawingType
 * 2. Phase 2 (REASON): observations summary is built → reasoning skills produce FINDING
 *    → hypotheses are created with trade parameters
 *
 * Prerequisites:
 * - MySQL running on localhost:3306/algotrading
 * - INFY instrument exists in the instruments table
 * - INFY 1h candles exist (for zigzag computation)
 *
 * Run with: ./gradlew test --tests "*.CopilotOrchestrationIntegrationTest" -Dspring.profiles.active=default
 */
@Slf4j
@SpringBootTest(classes = KiteconApplication.class)
@ActiveProfiles("integration")
@Disabled("Requires real MySQL — will be replaced by Testcontainers in T6.2")
class CopilotOrchestrationIntegrationTest {

    private static final String SYMBOL = "INFY";
    private static final String TIMEFRAME = "1h";
    private static final Long TEST_USER_ID = 1L;

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
    @Autowired private ObjectMapper objectMapper;

    @MockBean private CopilotAIService aiService;

    private Instrument instrument;
    private CopilotSkill doubleBottomSkill;

    @BeforeEach
    void setUp() {
        // Look up the real INFY instrument
        instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(SYMBOL, new String[]{"NSE"});
        assertNotNull(instrument, "INFY instrument must exist in the database. Run instrument sync first.");

        // Ensure a double_bottom skill exists for this user
        doubleBottomSkill = ensureDoubleBottomSkill();

        // Seed reasoning skills if not present
        skillService.seedReasoningSkillsForUser(TEST_USER_ID);
    }

    // ─── Test 1: Full pipeline — scan detects double bottom, reason creates hypothesis ──

    @Test
    void fullPipeline_scanDetectsDoubleBottom_reasonCreatesHypothesis() throws Exception {
        // ─── Arrange: get real market data ───────────────────────────────────────

        log.info("=== Fetching real zigzag pivots for {} {} ===", SYMBOL, TIMEFRAME);
        List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(SYMBOL, instrument, Interval.OneHour);
        assertFalse(pivots.isEmpty(), "ZigZag pivots must be available for INFY 1h");
        log.info("Got {} zigzag pivots", pivots.size());

        // Log last 10 pivots for visibility
        int start = Math.max(0, pivots.size() - 10);
        for (ZigZagPoint p : pivots.subList(start, pivots.size())) {
            log.info("  {} {:.2f} @ {} retr={:.1f}% ext={:.1f}%",
                    p.isHigh() ? "HIGH" : "LOW", p.getValue(), p.getTimestamp(),
                    p.getRetracementPct() != null ? p.getRetracementPct() : 0.0,
                    p.getExtensionPct() != null ? p.getExtensionPct() : 0.0);
        }

        // Compute market structure
        MarketStructureData msd = marketStructureService.analyse(pivots, TIMEFRAME);
        log.info("Market structure trend: {}", msd.getTrendDirection());
        log.info("Market structure summary:\n{}", msd.toPromptSummary());

        // ─── Create investigation with real data ─────────────────────────────────

        CopilotInvestigation investigation = investigationService.startInvestigation(
                999L, TEST_USER_ID, SYMBOL, TIMEFRAME, 60, "TEST");

        // Build zigzag summary (same logic as controller)
        StringBuilder zigzagSummary = new StringBuilder("=== " + TIMEFRAME + " ===\n");
        int zzStart = Math.max(0, pivots.size() - 10);
        for (ZigZagPoint p : pivots.subList(zzStart, pivots.size())) {
            zigzagSummary.append(String.format("  %s %.2f @ %s retr=%.1f%% ext=%.1f%%%n",
                    p.isHigh() ? "HIGH" : "LOW", p.getValue(), p.getTimestamp(),
                    p.getRetracementPct() != null ? p.getRetracementPct() : 0.0,
                    p.getExtensionPct() != null ? p.getExtensionPct() : 0.0));
        }

        investigationService.updateData(investigation.getId(),
                zigzagSummary.toString(), msd.toPromptSummary(), null);

        investigation = investigationService.getOrThrow(investigation.getId());

        assertNotNull(investigation.getZigzagData(), "Zigzag data should be stored");
        assertNotNull(investigation.getMarketStructureData(), "Market structure should be stored");
        log.info("Investigation #{} created with real market data", investigation.getId());

        // ─── Phase 1: SCAN — mock AI to return double bottom observation ─────────

        // Find two LOW pivots that could form a double bottom
        // (Use real pivot data for realistic drawingPoints)
        ZigZagPoint lastLow = null;
        ZigZagPoint prevLow = null;
        for (int i = pivots.size() - 1; i >= 0; i--) {
            if (!pivots.get(i).isHigh()) {
                if (lastLow == null) lastLow = pivots.get(i);
                else if (prevLow == null) { prevLow = pivots.get(i); break; }
            }
        }
        assertNotNull(lastLow, "Must have at least one LOW pivot");
        assertNotNull(prevLow, "Must have at least two LOW pivots");

        // Also find the high between the two lows (the neckline)
        ZigZagPoint necklineHigh = null;
        for (int i = pivots.size() - 1; i >= 0; i--) {
            ZigZagPoint p = pivots.get(i);
            if (p.isHigh() && p.getTimestamp().isAfter(prevLow.getTimestamp())
                    && p.getTimestamp().isBefore(lastLow.getTimestamp())) {
                necklineHigh = p;
                break;
            }
        }

        // Build the mock AI response for scan — a detected double bottom
        String scanResponse = buildScanObservationResponse(prevLow, necklineHigh, lastLow);
        log.info("Mock scan response:\n{}", scanResponse);

        // Mock AI service — any call during scan returns our observation response
        when(aiService.call(eq(TEST_USER_ID), anyString(), anyString()))
                .thenReturn(scanResponse);

        // ─── Execute Phase 1: SCAN ───────────────────────────────────────────────

        log.info("=== Running Phase 1: SCAN ===");
        String scanSkillPrompt = skillService.buildScanSkillPrompt(doubleBottomSkill);
        log.info("Scan skill prompt ({} chars):\n{}", scanSkillPrompt.length(),
                scanSkillPrompt.substring(0, Math.min(500, scanSkillPrompt.length())));

        String scanContext = orchestratorService.buildScanPrompt(scanSkillPrompt, investigation);
        log.info("Full scan context ({} chars)", scanContext.length());

        // Verify the scan prompt includes the key elements
        assertTrue(scanContext.contains("SCAN MODE"), "Scan prompt must instruct SCAN MODE");
        assertTrue(scanContext.contains("OBSERVATION"), "Scan prompt must reference OBSERVATION type");
        assertTrue(scanContext.contains("drawingPoints"), "Scan prompt must mention drawingPoints");
        assertTrue(scanContext.contains(SYMBOL), "Scan prompt must include symbol");
        assertTrue(scanContext.contains("ZIGZAG PIVOTS") || scanContext.contains("MARKET STRUCTURE"),
                "Scan prompt must include real market data");

        // Parse the mock response
        AIResponse parsedScan = responseParser.parse(scanResponse);
        assertInstanceOf(ObservationResponse.class, parsedScan, "Should parse as ObservationResponse");
        ObservationResponse obs = (ObservationResponse) parsedScan;
        assertTrue(obs.getPatternDetected(), "Double bottom should be detected");
        assertEquals("double_bottom", obs.getPatternType());
        assertEquals("HIGH", obs.getConfidence());
        assertNotNull(obs.getDrawingPoints(), "drawingPoints must be set");
        assertFalse(obs.getDrawingPoints().isEmpty(), "drawingPoints must not be empty");
        assertEquals("head_and_shoulders", obs.getDrawingType(), "Double bottom uses inverted H&S shape");

        // Persist the observation
        CopilotObservation savedObs = observationService.createFromScan(
                investigation.getId(), "double_bottom", obs, scanResponse);
        investigationService.recordSkillInvoked(investigation.getId(), "double_bottom", scanResponse);
        investigationService.updatePhase(investigation.getId(), "SCANNED");

        assertNotNull(savedObs.getId(), "Observation should be persisted with an ID");
        assertTrue(savedObs.getPatternDetected());
        assertEquals("double_bottom", savedObs.getPatternType());
        assertEquals("HIGH", savedObs.getConfidence());
        assertNotNull(savedObs.getDrawingPoints(), "Drawing points should be persisted as JSON");

        log.info("Phase 1 COMPLETE: observation #{} persisted — {} detected with {} confidence",
                savedObs.getId(), savedObs.getPatternType(), savedObs.getConfidence());

        // ─── Verify observation summary for Phase 2 ──────────────────────────────

        String obsSummary = observationService.buildObservationsSummary(investigation.getId());
        log.info("Observations summary for reasoning:\n{}", obsSummary);
        assertTrue(obsSummary.contains("DETECTED"), "Summary must show DETECTED");
        assertTrue(obsSummary.contains("double_bottom"), "Summary must include pattern type");
        assertTrue(obsSummary.contains("HIGH"), "Summary must include confidence");

        // ─── Phase 2: REASON — mock AI to return a finding/hypothesis ────────────

        String reasonResponse = buildReasonFindingResponse(prevLow, necklineHigh, lastLow);
        log.info("Mock reason response:\n{}", reasonResponse);

        // Reset mock for phase 2
        reset(aiService);
        when(aiService.call(eq(TEST_USER_ID), anyString(), anyString()))
                .thenReturn(reasonResponse);

        // Build reasoning prompt
        List<CopilotSkill> reasoningSkills = skillService.getReasoningSkillsForUser(TEST_USER_ID);
        assertFalse(reasoningSkills.isEmpty(), "Reasoning skills should exist (auto-seeded)");
        log.info("Reasoning skills: {}", reasoningSkills.stream().map(CopilotSkill::getSkillKey).toList());

        investigation = investigationService.getOrThrow(investigation.getId());
        CopilotSkill reasonSkill = reasoningSkills.get(0);
        String reasonSkillPrompt = skillService.buildSkillPrompt(reasonSkill);
        String reasonContext = orchestratorService.buildReasoningPrompt(
                reasonSkillPrompt, investigation, obsSummary, null, null, null);
        log.info("Reasoning context ({} chars)", reasonContext.length());

        assertTrue(reasonContext.contains("SCAN OBSERVATIONS"), "Reasoning prompt must include observations");
        assertTrue(reasonContext.contains("double_bottom"), "Reasoning prompt must reference detected pattern");

        // Parse reason response
        AIResponse parsedReason = responseParser.parse(reasonResponse);
        assertInstanceOf(FindingResponse.class, parsedReason, "Should parse as FindingResponse");
        FindingResponse finding = (FindingResponse) parsedReason;
        assertEquals("Double Bottom Reversal — INFY 1h", finding.getHypothesisLabel());
        assertNotNull(finding.getAnticipatoryEntry(), "Must have anticipatory entry");
        assertNotNull(finding.getConfirmationEntry(), "Must have confirmation entry");

        // Create hypothesis from finding
        var hypothesis = hypothesisService.createFromFinding(investigation.getId(), finding);
        investigationService.updatePhase(investigation.getId(), "REASONED");

        assertNotNull(hypothesis.getId(), "Hypothesis should be persisted");
        assertEquals("WATCHING", hypothesis.getState(), "New hypothesis should be WATCHING");
        log.info("Phase 2 COMPLETE: hypothesis #{} created — '{}' [{}]",
                hypothesis.getId(), hypothesis.getLabel(), hypothesis.getState());

        // ─── Verify end-to-end ───────────────────────────────────────────────────

        investigation = investigationService.getOrThrow(investigation.getId());
        assertEquals("REASONED", investigation.getPhase(), "Investigation phase should be REASONED");

        List<CopilotObservation> allObs = observationService.getObservationsForInvestigation(investigation.getId());
        assertEquals(1, allObs.size(), "Should have 1 observation");
        assertTrue(allObs.get(0).getPatternDetected());

        var allHypotheses = hypothesisService.getAllHypotheses(investigation.getId());
        assertEquals(1, allHypotheses.size(), "Should have 1 hypothesis");
        assertEquals("Double Bottom Reversal — INFY 1h", allHypotheses.get(0).getLabel());

        log.info("=== FULL PIPELINE VALIDATED ===");
        log.info("  Investigation: #{} [{}]", investigation.getId(), investigation.getPhase());
        log.info("  Observations: {} (detected: {})", allObs.size(),
                allObs.stream().filter(CopilotObservation::getPatternDetected).count());
        log.info("  Hypotheses: {} (active: {})", allHypotheses.size(),
                allHypotheses.stream().filter(h -> "WATCHING".equals(h.getState())).count());
    }

    // ─── Test 2: Verify scan prompt includes real market data ────────────────────

    @Test
    void scanPrompt_includesRealZigzagAndMarketStructure() {
        List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(SYMBOL, instrument, Interval.OneHour);
        assertFalse(pivots.isEmpty());

        MarketStructureData msd = marketStructureService.analyse(pivots, TIMEFRAME);

        CopilotInvestigation investigation = investigationService.startInvestigation(
                998L, TEST_USER_ID, SYMBOL, TIMEFRAME, 60, "TEST");

        // Store market data
        StringBuilder zigzagSummary = new StringBuilder("=== " + TIMEFRAME + " ===\n");
        int zzStart = Math.max(0, pivots.size() - 10);
        for (ZigZagPoint p : pivots.subList(zzStart, pivots.size())) {
            zigzagSummary.append(String.format("  %s %.2f @ %s%n",
                    p.isHigh() ? "HIGH" : "LOW", p.getValue(), p.getTimestamp()));
        }
        investigationService.updateData(investigation.getId(),
                zigzagSummary.toString(), msd.toPromptSummary(), null);
        investigation = investigationService.getOrThrow(investigation.getId());

        // Build scan prompt
        String skillPrompt = skillService.buildScanSkillPrompt(doubleBottomSkill);
        String fullPrompt = orchestratorService.buildScanPrompt(skillPrompt, investigation);

        log.info("Full scan prompt:\n{}", fullPrompt);

        // Assertions on prompt content
        assertTrue(fullPrompt.contains("SCAN MODE"));
        assertTrue(fullPrompt.contains("OBSERVATION"));
        assertTrue(fullPrompt.contains("drawingPoints"));
        assertTrue(fullPrompt.contains("drawingType"));
        assertTrue(fullPrompt.contains("INFY"));
        assertTrue(fullPrompt.contains("ZIGZAG PIVOTS"));
        assertTrue(fullPrompt.contains("HIGH") || fullPrompt.contains("LOW"),
                "Must contain actual pivot labels");

        // Verify real price values are in the prompt (not just placeholders)
        boolean hasNumericPrice = fullPrompt.matches("(?s).*\\d{3,5}\\.\\d{2}.*");
        assertTrue(hasNumericPrice, "Prompt must contain real numeric prices from INFY data");

        // Verify skill sections included
        assertTrue(fullPrompt.contains("IDENTIFICATION RULES"));
        assertTrue(fullPrompt.contains("STAGE DETECTION"));
        assertTrue(fullPrompt.contains("INVALIDATION RULES"));
        assertTrue(fullPrompt.contains("CROSS-VERIFICATION RULES"));

        // Verify entry/indicator rules are EXCLUDED in scan mode
        assertFalse(fullPrompt.contains("ENTRY RULES PER STAGE"),
                "Scan mode should NOT include entry rules");
        assertFalse(fullPrompt.contains("INDICATOR RULES PER STAGE"),
                "Scan mode should NOT include indicator rules");
    }

    // ─── Test 3: AIResponseParser handles OBSERVATION type ───────────────────────

    @Test
    void responseParser_handlesObservationType() {
        String json = """
            {
              "type": "OBSERVATION",
              "patternDetected": true,
              "patternType": "double_bottom",
              "confidence": "MEDIUM",
              "structuralDetails": "Two lows at similar levels",
              "stage": "neckline_approach",
              "keyLevels": [{"price": 1500.0, "label": "neckline"}],
              "drawingPoints": [
                {"time": 1711234567, "price": 1450.0, "label": "first_low"},
                {"time": 1711334567, "price": 1500.0, "label": "neckline"},
                {"time": 1711434567, "price": 1455.0, "label": "second_low"}
              ],
              "drawingType": "head_and_shoulders",
              "timeframe": "1h",
              "contradictions": ["Volume not confirming"],
              "reasoning": "Clear double bottom structure visible"
            }
            """;

        AIResponse response = responseParser.parse(json);
        assertInstanceOf(ObservationResponse.class, response);

        ObservationResponse obs = (ObservationResponse) response;
        assertTrue(obs.getPatternDetected());
        assertEquals("double_bottom", obs.getPatternType());
        assertEquals("MEDIUM", obs.getConfidence());
        assertEquals("head_and_shoulders", obs.getDrawingType());
        assertEquals(3, obs.getDrawingPoints().size());
        assertEquals(1, obs.getContradictions().size());
        assertEquals("neckline_approach", obs.getStage());
    }

    // ─── Test 4: Observation summary builds correctly for reasoning ──────────────

    @Test
    void observationSummary_buildsCorrectlyForReasoning() {
        CopilotInvestigation investigation = investigationService.startInvestigation(
                997L, TEST_USER_ID, SYMBOL, TIMEFRAME, 60, "TEST");

        // Create a positive observation
        ObservationResponse obsResponse = ObservationResponse.builder()
                .patternDetected(true)
                .patternType("double_bottom")
                .confidence("HIGH")
                .structuralDetails("Two equal lows at 1450 with neckline at 1500")
                .stage("breakout_pending")
                .reasoning("Classic W pattern with volume divergence")
                .build();

        observationService.createFromScan(investigation.getId(), "double_bottom", obsResponse, "{}");

        // Create a negative observation
        ObservationResponse negObs = ObservationResponse.builder()
                .patternDetected(false)
                .patternType(null)
                .confidence(null)
                .reasoning("No triangle structure visible")
                .build();

        observationService.createFromScan(investigation.getId(), "triangle", negObs, "{}");

        String summary = observationService.buildObservationsSummary(investigation.getId());
        log.info("Observation summary:\n{}", summary);

        assertTrue(summary.contains("SCAN OBSERVATIONS"));
        assertTrue(summary.contains("double_bottom"));
        assertTrue(summary.contains("DETECTED"));
        assertTrue(summary.contains("NOT DETECTED"));
        assertTrue(summary.contains("HIGH"));
        assertTrue(summary.contains("breakout_pending"));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    private CopilotSkill ensureDoubleBottomSkill() {
        Optional<CopilotSkill> existing = skillRepository.findByUserIdAndSkillKey(TEST_USER_ID, "double_bottom");
        if (existing.isPresent()) return existing.get();

        return skillService.saveSkill(CopilotSkill.builder()
                .userId(TEST_USER_ID)
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

    private String buildScanObservationResponse(ZigZagPoint firstLow, ZigZagPoint neckline, ZigZagPoint secondLow) {
        long firstLowTime = firstLow.getTimestamp().getEpochSecond();
        double firstLowPrice = firstLow.getValue();
        long secondLowTime = secondLow.getTimestamp().getEpochSecond();
        double secondLowPrice = secondLow.getValue();

        long necklineTime = neckline != null ? neckline.getTimestamp().getEpochSecond()
                : (firstLowTime + secondLowTime) / 2;
        double necklinePrice = neckline != null ? neckline.getValue()
                : Math.max(firstLowPrice, secondLowPrice) * 1.03;

        return String.format("""
            {
              "type": "OBSERVATION",
              "patternDetected": true,
              "patternType": "double_bottom",
              "confidence": "HIGH",
              "structuralDetails": "Two lows at %.2f and %.2f with neckline at %.2f. Classic W reversal pattern. Second low held above first, indicating buyer support. The two lows are within 1.5%% of each other.",
              "stage": "breakout_pending",
              "keyLevels": [
                {"price": %.2f, "label": "first_low"},
                {"price": %.2f, "label": "neckline"},
                {"price": %.2f, "label": "second_low"},
                {"price": %.2f, "label": "measured_move_target"}
              ],
              "drawingPoints": [
                {"time": %d, "price": %.2f, "label": "first_low"},
                {"time": %d, "price": %.2f, "label": "neckline"},
                {"time": %d, "price": %.2f, "label": "second_low"}
              ],
              "drawingType": "head_and_shoulders",
              "timeframe": "1h",
              "contradictions": [],
              "reasoning": "Clear double bottom visible on INFY 1h. Two lows at similar levels with a rally between them forming the neckline. Volume decreased on the second low suggesting selling exhaustion. RSI shows bullish divergence. Pattern currently in breakout_pending stage approaching neckline resistance."
            }
            """,
                firstLowPrice, secondLowPrice, necklinePrice,
                firstLowPrice, necklinePrice, secondLowPrice,
                necklinePrice + (necklinePrice - Math.min(firstLowPrice, secondLowPrice)),
                firstLowTime, firstLowPrice,
                necklineTime, necklinePrice,
                secondLowTime, secondLowPrice);
    }

    private String buildReasonFindingResponse(ZigZagPoint firstLow, ZigZagPoint neckline, ZigZagPoint secondLow) {
        double low = Math.min(firstLow.getValue(), secondLow.getValue());
        double neck = neckline != null ? neckline.getValue() : low * 1.03;
        double measuredMove = neck + (neck - low);

        return String.format("""
            {
              "type": "FINDING",
              "hypothesisLabel": "Double Bottom Reversal — INFY 1h",
              "hypothesisDescription": "Double bottom W pattern identified with two equal lows and neckline resistance. Pattern in breakout_pending stage. Trade opportunity exists on neckline break or anticipatory entry at support.",
              "waveContext": "Potential end of corrective wave — double bottom may mark reversal point",
              "pattern": "double_bottom",
              "currentStage": "breakout_pending",
              "confidenceLayers": {
                "pattern_structure": "pass",
                "volume_confirmation": "pass",
                "rsi_divergence": "pass",
                "higher_tf_alignment": "pending"
              },
              "anticipatoryEntry": {
                "entryZone": "%.2f - %.2f (second low support zone)",
                "sl": "%.2f (below both lows with buffer)",
                "tp": "%.2f (measured move target)",
                "conditionsNeeded": ["Hold at second low zone", "RSI bullish divergence confirmed", "Lower TF reversal candle"],
                "triggerDescription": "Enter long when price holds at second low zone with reversal candle on 15min"
              },
              "confirmationEntry": {
                "entryZone": "%.2f (neckline retest after break)",
                "sl": "%.2f (below neckline)",
                "tp": "%.2f (measured move from breakout)",
                "conditionsNeeded": ["Neckline break with volume", "Successful retest of neckline as support"],
                "triggerDescription": "Enter long on pullback to neckline after confirmed break above"
              },
              "invalidationConditions": [
                "Price breaks below %.2f (below both lows)",
                "Neckline break fails — immediate selloff back below neckline",
                "RSI fails to show divergence on second low"
              ],
              "anomalyFlags": [],
              "relationships": [],
              "suggestNextSkills": [],
              "reasoning": "Confluence checker finds strong double bottom setup. Pattern structure confirmed with two equal lows. RSI divergence provides additional confidence. Volume profile supports accumulation at the lows. Trade active with anticipatory entry at support or confirmation entry on neckline break."
            }
            """,
                low * 0.995, low * 1.005,
                low * 0.98,
                measuredMove,
                neck, neck * 0.99, measuredMove,
                low * 0.97);
    }
}
