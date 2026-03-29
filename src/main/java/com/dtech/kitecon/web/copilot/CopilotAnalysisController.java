package com.dtech.kitecon.web.copilot;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.data.ChartLayout;
import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.data.copilot.CopilotObservation;
import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.repository.ChartLayoutRepository;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.service.copilot.*;
import com.dtech.kitecon.service.copilot.dto.*;
import com.dtech.ta.elliott.ElliottWaveAnalysis;
import com.dtech.ta.elliott.ElliottWaveAnalyzer;
import com.dtech.ta.elliott.WaveCount;
import com.dtech.ta.elliott.WaveScenario;
import com.dtech.ta.elliott.AdvancedElliottAnalysisResult;
import com.dtech.ta.elliott.AdvancedElliottService;
import com.dtech.ta.elliott.ElliottVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.ta4j.core.BarSeries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API for triggering and managing Co-Pilot investigations.
 *
 * POST /api/analysis/scan       — Phase 1: run all scan skills, return observations
 * POST /api/analysis/reason     — Phase 2: cross-correlate observations, return hypotheses
 * POST /api/analysis/trigger    — convenience: runs scan then reason sequentially
 * GET  /api/analysis/observations — get observations for an investigation
 * POST /api/analysis/respond    — submit expert response to a NEEDS_EXPERT question
 * POST /api/analysis/confirm-wave — expert confirms or rejects system-proposed wave count
 */
@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
public class CopilotAnalysisController {

    private final CopilotInvestigationService investigationService;
    private final CopilotSkillService skillService;
    private final CopilotOrchestratorService orchestratorService;
    private final CopilotHypothesisService hypothesisService;
    private final CopilotObservationService observationService;
    private final CopilotAIService aiService;
    private final AIResponseParser responseParser;
    private final MarketStructureService marketStructureService;
    private final ZigZagService zigzagService;
    private final ElliottWaveAnalyzer elliottWaveAnalyzer;
    private final com.dtech.kitecon.analysis.AnalysisProcessService analysisProcessService;
    private final AdvancedElliottService advancedElliottService;
    private final ElliottVerificationService elliottVerificationService;
    private final InstrumentRepository instrumentRepository;
    private final UserRepository userRepository;
    private final ChartLayoutRepository chartLayoutRepository;

    // ─── Phase 1: Scan ─────────────────────────────────────────────────────────

    /**
     * Phase 1: Run all active scan skills to detect patterns/waves.
     * Returns observations (not hypotheses).
     *
     * Body: { layoutId, symbol, drawingsJson, timeframes[], force }
     */
    @PostMapping("/scan")
    public ResponseEntity<?> scanAnalysis(Authentication auth,
                                           @RequestBody Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        CopilotInvestigation investigation = createOrReuseInvestigation(userId, body);

        String warning = null;
        List<CopilotObservation> observations = new ArrayList<>();

        try {
            observations = runScanPhase(investigation, userId);
        } catch (Exception e) {
            warning = e.getMessage();
            log.error("[Copilot] Scan failed for investigation #{}: {}", investigation.getId(), e.getMessage(), e);
        }

        var result = new java.util.HashMap<String, Object>();
        result.put("investigationId", investigation.getId());
        result.put("status", "scanned");
        result.put("observations", observations);
        if (warning != null) result.put("warning", warning);
        return ResponseEntity.ok(result);
    }

    // ─── Phase 2: Reason ─────────────────────────────────────────────────────

    /**
     * Phase 2: Cross-correlate observations to find confluence.
     * Can be invoked independently with observations, drawings, scenarios, or prior hypotheses.
     *
     * Body: ReasoningRequest JSON
     */
    @PostMapping("/reason")
    public ResponseEntity<?> reasonAnalysis(Authentication auth,
                                             @RequestBody ReasoningRequest request) {
        Long userId = resolveUserId(auth);
        CopilotInvestigation investigation = investigationService.getOrThrow(request.getInvestigationId());

        String warning = null;

        try {
            runReasonPhase(investigation, userId, request);
        } catch (Exception e) {
            warning = e.getMessage();
            log.error("[Copilot] Reasoning failed for investigation #{}: {}", investigation.getId(), e.getMessage(), e);
        }

        investigation = investigationService.getOrThrow(investigation.getId());

        var result = new java.util.HashMap<String, Object>();
        result.put("investigationId", investigation.getId());
        result.put("status", "reasoned");
        result.put("hypotheses", hypothesisService.getAllHypotheses(investigation.getId()));
        result.put("flags", hypothesisService.getUnacknowledgedFlags(investigation.getId()));
        result.put("observations", observationService.getObservationsForInvestigation(investigation.getId()));
        if (warning != null) result.put("warning", warning);
        return ResponseEntity.ok(result);
    }

    // ─── Combined: Scan + Reason ─────────────────────────────────────────────

    /**
     * Convenience endpoint: runs scan then reason sequentially.
     * Backward-compatible with the old trigger endpoint, now enhanced with observations.
     *
     * Body: { layoutId, symbol, drawingsJson, timeframes[], force }
     */
    @PostMapping("/trigger")
    public ResponseEntity<?> triggerAnalysis(Authentication auth,
                                              @RequestBody Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        CopilotInvestigation investigation = createOrReuseInvestigation(userId, body);
        if (investigation == null) {
            // Returned existing investigation in createOrReuseInvestigation — need to handle
            Long layoutId = Long.valueOf(body.get("layoutId").toString());
            boolean force = Boolean.parseBoolean(body.getOrDefault("force", "false").toString());
            if (!force) {
                Optional<CopilotInvestigation> existing = investigationService.getActiveInvestigation(layoutId, userId);
                if (existing.isPresent()) {
                    return ResponseEntity.ok(Map.of(
                            "investigationId", existing.get().getId(),
                            "status", "existing",
                            "message", "Active investigation found. Use existing or wait for expiry.",
                            "hypotheses", hypothesisService.getAllHypotheses(existing.get().getId()),
                            "flags", hypothesisService.getUnacknowledgedFlags(existing.get().getId()),
                            "observations", observationService.getObservationsForInvestigation(existing.get().getId())
                    ));
                }
            }
            return ResponseEntity.badRequest().body(Map.of("error", "Failed to create investigation"));
        }

        String warning = null;
        List<CopilotObservation> observations = new ArrayList<>();

        // Phase 1: Scan
        try {
            observations = runScanPhase(investigation, userId);
        } catch (Exception e) {
            warning = "Scan: " + e.getMessage();
            log.error("[Copilot] Scan failed for investigation #{}: {}", investigation.getId(), e.getMessage(), e);
        }

        // Phase 2: Reason (only if scan produced positive observations)
        boolean hasPositive = observations.stream().anyMatch(CopilotObservation::getPatternDetected);
        if (hasPositive) {
            try {
                ReasoningRequest reasonRequest = ReasoningRequest.builder()
                        .investigationId(investigation.getId())
                        .drawingsJson((String) body.getOrDefault("drawingsJson", null))
                        .build();
                runReasonPhase(investigation, userId, reasonRequest);
            } catch (Exception e) {
                String reasonWarning = "Reason: " + e.getMessage();
                warning = warning != null ? warning + "; " + reasonWarning : reasonWarning;
                log.error("[Copilot] Reasoning failed for investigation #{}: {}", investigation.getId(), e.getMessage(), e);
            }
        }

        investigation = investigationService.getOrThrow(investigation.getId());

        var result = new java.util.HashMap<String, Object>();
        result.put("investigationId", investigation.getId());
        result.put("status", "created");
        result.put("observations", observationService.getObservationsForInvestigation(investigation.getId()));
        result.put("hypotheses", hypothesisService.getAllHypotheses(investigation.getId()));
        result.put("flags", hypothesisService.getUnacknowledgedFlags(investigation.getId()));
        if (warning != null) result.put("warning", warning);
        return ResponseEntity.ok(result);
    }

    // ─── Observations query ──────────────────────────────────────────────────

    @GetMapping("/observations")
    public ResponseEntity<?> getObservations(@RequestParam Long investigationId) {
        return ResponseEntity.ok(observationService.getObservationsForInvestigation(investigationId));
    }

    /**
     * Submit expert response to a NEEDS_EXPERT question.
     * Body: { investigationId, questionId, expertResponse, override (bool) }
     */
    @PostMapping("/respond")
    public ResponseEntity<?> respondToQuestion(Authentication auth,
                                                @RequestBody Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        Long investigationId = Long.valueOf(body.get("investigationId").toString());
        String questionId = (String) body.get("questionId");
        String expertResponse = (String) body.get("expertResponse");
        boolean override = Boolean.parseBoolean(body.getOrDefault("override", "false").toString());

        CopilotInvestigation investigation = investigationService.getOrThrow(investigationId);

        // Record the expert override if applicable
        if (override) {
            String currentOverrides = investigation.getExpertOverrides();
            String entry = String.format("{\"questionId\":\"%s\",\"response\":\"%s\",\"override\":true}",
                    questionId, expertResponse.replace("\"", "'"));
            investigation.setExpertOverrides(
                    currentOverrides.equals("[]")
                            ? "[" + entry + "]"
                            : currentOverrides.substring(0, currentOverrides.lastIndexOf(']')) + "," + entry + "]");
        }

        return ResponseEntity.ok(Map.of(
                "status", "response_recorded",
                "questionId", questionId,
                "hypotheses", hypothesisService.getAllHypotheses(investigationId),
                "flags", hypothesisService.getUnacknowledgedFlags(investigationId)
        ));
    }

    /**
     * Expert confirms or rejects a system-proposed wave count.
     * Body: { investigationId, confirmed (bool), correction (optional) }
     */
    @PostMapping("/confirm-wave")
    public ResponseEntity<?> confirmWaveCount(Authentication auth,
                                               @RequestBody Map<String, Object> body) {
        Long investigationId = Long.valueOf(body.get("investigationId").toString());
        boolean confirmed = Boolean.parseBoolean(body.get("confirmed").toString());
        String source = confirmed ? "EXPERT_CONFIRMED" : "EXPERT_REJECTED";

        CopilotInvestigation investigation = investigationService.confirmWaveCount(
                investigationId, confirmed, source);

        return ResponseEntity.ok(Map.of(
                "investigationId", investigation.getId(),
                "waveCountConfirmed", investigation.getWaveCountConfirmed(),
                "waveCountSource", investigation.getWaveCountSource()
        ));
    }

    // ─── Phase execution helpers ─────────────────────────────────────────────

    /**
     * Create or reuse an investigation from request body params.
     * Returns null if an existing active investigation was found and force=false.
     */
    private CopilotInvestigation createOrReuseInvestigation(Long userId, Map<String, Object> body) {
        Long layoutId = Long.valueOf(body.get("layoutId").toString());
        String symbol = (String) body.get("symbol");
        String drawingsJson = (String) body.getOrDefault("drawingsJson", null);
        String initiatedBy = (String) body.getOrDefault("initiatedBy", "LAYOUT_OPEN");
        boolean force = Boolean.parseBoolean(body.getOrDefault("force", "false").toString());

        @SuppressWarnings("unchecked")
        List<String> timeframes = (List<String>) body.get("timeframes");
        if (timeframes == null || timeframes.isEmpty()) {
            log.warn("[Copilot] No timeframes sent in scan request for layout {} symbol {} — using active tab fallback",
                    layoutId, symbol);
            timeframes = List.of("1d", "1h", "15m");
        }

        int validityMinutes = chartLayoutRepository.findById(layoutId)
                .map(layout -> layout.getCopilotValidityMinutes() != null
                        ? layout.getCopilotValidityMinutes() : 60)
                .orElse(60);

        if (!force) {
            Optional<CopilotInvestigation> existing = investigationService.getActiveInvestigation(layoutId, userId);
            if (existing.isPresent()) return null;
        }

        CopilotInvestigation investigation = investigationService.startInvestigation(
                layoutId, userId, symbol, String.join(",", timeframes), validityMinutes, initiatedBy);

        if (drawingsJson != null) {
            investigationService.updateData(investigation.getId(), null, null, drawingsJson);
        }

        fetchAndStoreMarketStructure(investigation.getId(), symbol, timeframes);
        return investigationService.getOrThrow(investigation.getId());
    }

    /**
     * Phase 1 (Scan): Single Scenario Evaluator call using pre-computed ElliottWaveAnalysis.
     * Replaces the 33-skill scan loop — AI evaluates/ranks pre-computed scenarios instead of detecting.
     */
    private List<CopilotObservation> runScanPhase(CopilotInvestigation investigation, Long userId) {
        if (investigation.getElliottAnalysisData() == null || investigation.getElliottAnalysisData().isBlank()) {
            log.warn("[Copilot] No Elliott analysis data for investigation #{} — skipping Scenario Evaluator",
                    investigation.getId());
            investigationService.updatePhase(investigation.getId(), "SCANNED");
            return List.of();
        }

        log.info("[Copilot] Phase 1 SCAN: running Scenario Evaluator for investigation #{}", investigation.getId());

        try {
            String instructions = orchestratorService.buildScenarioEvaluatorInstructions();
            String data = orchestratorService.buildScenarioEvaluatorData(investigation);
            log.info("[Copilot] Scenario Evaluator breakdown — instructions={}chars elliott={}chars msd={}chars drawings={}chars total={}chars",
                    instructions.length(),
                    investigation.getElliottAnalysisData()  != null ? investigation.getElliottAnalysisData().length()  : 0,
                    investigation.getMarketStructureData()  != null ? investigation.getMarketStructureData().length()  : 0,
                    investigation.getDrawingsData()          != null ? investigation.getDrawingsData().length()          : 0,
                    instructions.length() + data.length());

            String rawResponse = aiService.call(userId, instructions, data);
            log.info("[Copilot] Scenario Evaluator raw response: {}", rawResponse);

            AIResponse response = responseParser.parse(rawResponse);
            handleSkillResponse(response, investigation, userId);
            investigationService.recordSkillInvoked(investigation.getId(), "scenario_evaluator", rawResponse);
        } catch (Exception e) {
            log.error("[Copilot] Scenario Evaluator failed for investigation #{}: {}",
                    investigation.getId(), e.getMessage(), e);
        }

        investigationService.updatePhase(investigation.getId(), "SCANNED");
        return List.of();
    }

    /**
     * Run Phase 2: reasoning skills to cross-correlate observations.
     */
    private void runReasonPhase(CopilotInvestigation investigation, Long userId,
                                 ReasoningRequest request) {
        String observationsSummary = observationService.buildObservationsSummary(investigation.getId());

        // Early exit if no observations and no other inputs
        if (observationsSummary.isBlank()
                && (request.getDrawingsJson() == null || request.getDrawingsJson().isBlank())
                && (request.getScenarioText() == null || request.getScenarioText().isBlank())
                && (request.getPriorHypothesisIds() == null || request.getPriorHypothesisIds().isEmpty())) {
            log.info("[Copilot] No observations or inputs for reasoning — skipping Phase 2");
            return;
        }

        List<CopilotSkill> reasoningSkills;
        if (request.getReasoningSkillKeys() != null && !request.getReasoningSkillKeys().isEmpty()) {
            // Force specific reasoning skills
            reasoningSkills = new ArrayList<>();
            for (String key : request.getReasoningSkillKeys()) {
                skillService.getSkillByKey(userId, key).ifPresent(reasoningSkills::add);
            }
        } else {
            reasoningSkills = skillService.getReasoningSkillsForUser(userId);
        }

        if (reasoningSkills.isEmpty()) {
            // Auto-seed reasoning skills if none exist
            skillService.seedReasoningSkillsForUser(userId);
            reasoningSkills = skillService.getReasoningSkillsForUser(userId);
        }

        if (reasoningSkills.isEmpty()) {
            log.warn("[Copilot] No reasoning skills available — skipping Phase 2");
            return;
        }

        log.info("[Copilot] Phase 2 REASON: running {} skills for investigation #{}",
                reasoningSkills.size(), investigation.getId());

        // Build prior hypotheses JSON if requested
        String priorHypothesesJson = null;
        if (request.getPriorHypothesisIds() != null && !request.getPriorHypothesisIds().isEmpty()) {
            priorHypothesesJson = hypothesisService.getHypothesesSummary(request.getPriorHypothesisIds());
        }

        for (CopilotSkill skill : reasoningSkills) {
            try {
                String skillPrompt = skillService.buildSkillPrompt(skill);
                String context = orchestratorService.buildReasoningPrompt(
                        skillPrompt, investigation, observationsSummary,
                        request.getDrawingsJson(), request.getScenarioText(), priorHypothesesJson);
                log.info("[Copilot] Reasoning skill '{}' (prompt {}chars)", skill.getSkillKey(), context.length());

                String rawResponse = aiService.call(userId, skillPrompt, context);
                log.info("[Copilot] Reasoning skill '{}' raw response: {}", skill.getSkillKey(), rawResponse);

                AIResponse response = responseParser.parse(rawResponse);
                handleSkillResponse(response, investigation, userId);
                investigation = investigationService.getOrThrow(investigation.getId());

            } catch (Exception e) {
                log.error("[Copilot] Error running reasoning skill '{}': {}", skill.getSkillKey(), e.getMessage(), e);
            }
        }

        investigationService.updatePhase(investigation.getId(), "REASONED");
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    /**
     * Fetch ZigZag pivots for each requested timeframe, compute market structure,
     * run the full Elliott Wave engine, and store all results in the investigation.
     */
    private void fetchAndStoreMarketStructure(Long investigationId, String symbol, List<String> timeframes) {
        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            log.warn("[Copilot] Instrument not found for symbol '{}' — skipping market structure fetch", symbol);
            return;
        }

        StringBuilder msdSummary = new StringBuilder();
        StringBuilder zigzagSummary = new StringBuilder();

        // Collect per-TF data for Elliott Wave engine
        Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
        Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
        Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();
        List<String> orderedTfs = new ArrayList<>();

        for (String tf : timeframes) {
            Interval interval = mapTimeframeToInterval(tf);
            if (interval == null) {
                log.warn("[Copilot] Unknown timeframe '{}' — skipping", tf);
                continue;
            }
            try {
                log.info("[Copilot] Fetching ZigZag for {} {}", symbol, tf);
                List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(symbol, instrument, interval);
                log.info("[Copilot] Got {} ZigZag pivots for {} {}", pivots.size(), symbol, tf);

                // Fetch BarSeries first so we can inject LTP as a synthetic last pivot
                BarSeries series = zigzagService.getBarSeries(symbol, instrument, interval);

                // Append/replace last pivot with current LTP to make analysis real-time aware
                List<ZigZagPoint> pivotsWithLtp = zigzagService.withLtpPivot(pivots, series);

                MarketStructureData msd = marketStructureService.analyse(pivotsWithLtp, tf);
                msdSummary.append(msd.toPromptSummary()).append("\n");

                // Zigzag summary: last 30 pivots (with LTP synthetic pivot)
                if (!pivotsWithLtp.isEmpty()) {
                    zigzagSummary.append("=== ").append(tf).append(" ===\n");
                    zigzagSummary.append(String.format("  Total pivots: %d (showing last %d, last is LTP-synthetic)%n",
                            pivotsWithLtp.size(), Math.min(30, pivotsWithLtp.size())));
                    int start = Math.max(0, pivotsWithLtp.size() - 30);
                    for (int i = start; i < pivotsWithLtp.size(); i++) {
                        ZigZagPoint p = pivotsWithLtp.get(i);
                        zigzagSummary.append(String.format("  [%d] %s %.2f @ %s retr=%.1f%% ext=%.1f%%%n",
                                i, p.isHigh() ? "HIGH" : "LOW", p.getValue(), p.getTimestamp(),
                                p.getRetracementPct() != null ? p.getRetracementPct() : 0.0,
                                p.getExtensionPct() != null ? p.getExtensionPct() : 0.0));
                    }
                }

                // Collect for Elliott Wave engine (use LTP-adjusted pivots)
                pivotsByTf.put(tf, pivotsWithLtp);
                structureByTf.put(tf, msd);
                seriesByTf.put(tf, series);
                orderedTfs.add(tf);

            } catch (Exception e) {
                log.warn("[Copilot] Failed to fetch market structure for {} {}: {}", symbol, tf, e.getMessage());
            }
        }

        // Store zigzag + market structure text
        String msdText = msdSummary.toString();
        String zzText = zigzagSummary.toString();
        if (!msdText.isBlank() || !zzText.isBlank()) {
            investigationService.updateData(investigationId,
                    zzText.isBlank() ? null : zzText,
                    msdText.isBlank() ? null : msdText,
                    null);
            log.info("[Copilot] Stored market structure + zigzag data for investigation #{}", investigationId);
        }

        // Run Elliott Wave engine (Layers 4-7) and store pre-computed analysis
        if (!orderedTfs.isEmpty()) {
            try {
                String primaryTf = orderedTfs.get(0); // first = highest resolution requested (or lowest — use last)
                // Use the most detailed timeframe that has data as primary
                String detailTf = orderedTfs.get(orderedTfs.size() - 1);
                log.info("[Copilot] Running Elliott Wave engine for {} — primary TF: {}", symbol, detailTf);

                ElliottWaveAnalysis analysis = elliottWaveAnalyzer.analyze(
                        pivotsByTf, structureByTf, seriesByTf, orderedTfs, detailTf);

                // Derive current price from last bar of the most detailed TF series
                org.ta4j.core.BarSeries detailSeries = seriesByTf.get(detailTf);
                double currentPrice = (detailSeries != null && detailSeries.getBarCount() > 0)
                        ? detailSeries.getLastBar().getClosePrice().doubleValue()
                        : 0.0;

                // Run processing pipeline: dedup + restructure + AI payload
                com.dtech.kitecon.analysis.dto.ProcessAnalysisResponse processed =
                        analysisProcessService.process(analysis, symbol, currentPrice, detailTf);

                // Store the restructured human-readable text (replaces raw toPromptSummary)
                String promptSummary = processed.getHumanReadable();
                investigationService.updateElliottData(investigationId, promptSummary);
                log.info("[Copilot] Stored processed Elliott analysis ({} chars, patterns {}->{}) for investigation #{}",
                        promptSummary.length(),
                        processed.getProcessingStats().getPatternsBefore(),
                        processed.getProcessingStats().getPatternsAfter(),
                        investigationId);
                log.info("[Copilot] Elliott analysis ready: primaryTf={}, waveCounts={}, patterns={}, scenarios={}",
                        analysis.getPrimaryTimeframe(),
                        analysis.getWaveCounts() != null ? analysis.getWaveCounts().size() : 0,
                        analysis.getAllPatterns() != null ? analysis.getAllPatterns().size() : 0,
                        analysis.getScenarios() != null ? analysis.getScenarios().size() : 0);

                if (analysis.getWaveCounts() != null) {
                    analysis.getWaveCounts().stream()
                            .sorted(Comparator.comparingInt(WaveCount::totalScore).reversed())
                            .limit(5)
                            .forEach(wc -> log.info(
                                    "[Copilot] WaveCount {} [{}] bullish={} score={} fib={} ind={} crossTf={} alt={} current={}",
                                    wc.getWaveType(),
                                    wc.getPrimaryTimeframe(),
                                    wc.isBullish(),
                                    wc.totalScore(),
                                    wc.getFibonacciScore(),
                                    wc.getIndicatorScore(),
                                    wc.getCrossTfScore(),
                                    wc.getAlternationScore(),
                                    wc.getCurrentPositionDescription()));
                }

                WaveScenario topScenario = analysis.topScenario();
                if (topScenario != null) {
                    log.info("[Copilot] Top scenario {} [{}] hypotheses={}",
                            topScenario.getId(), topScenario.getDirectionLabel(),
                            topScenario.getHypotheses() != null ? topScenario.getHypotheses().size() : 0);
                    if (topScenario.getHypotheses() != null) {
                        topScenario.getHypotheses().stream().limit(3).forEach(h ->
                                log.info("[Copilot]   Hypothesis {} score={} position={} invalidation={} target={}",
                                        h.getId(),
                                        h.getTotalScore(),
                                        h.getCurrentPositionDescription(),
                                        h.getInvalidationLevel(),
                                        h.getPrimaryTarget() != null ? h.getPrimaryTarget().getLevel() : null));
                    }
                }

                if (analysis.getNestedBranchNarrative() != null && !analysis.getNestedBranchNarrative().isBlank()) {
                    log.info("[Copilot] Nested corrective branch context:\n{}",
                            analysis.getNestedBranchNarrative());
                }
            } catch (Exception e) {
                log.error("[Copilot] Elliott Wave engine failed for {} investigation #{}: {}",
                        symbol, investigationId, e.getMessage(), e);
            }
        }
    }

    private Interval mapTimeframeToInterval(String tf) {
        if (tf == null) return null;
        return switch (tf.toLowerCase().trim()) {
            case "weekly", "1w", "week"             -> Interval.Week;
            case "daily", "1d", "day"               -> Interval.Day;
            case "4h", "4hour", "240"             -> Interval.FourHours;
            case "1h", "60min", "60minute"          -> Interval.OneHour;
            case "30min", "30m", "30minute"         -> Interval.ThirtyMinute;
            case "15min", "15m", "15minute"         -> Interval.FifteenMinute;
            case "5min", "5m", "5minute"            -> Interval.FiveMinute;
            case "3min", "3m", "3minute"            -> Interval.ThreeMinute;
            case "1min", "1m", "1minute"            -> Interval.OneMinute;
            default -> null;
        };
    }

    private void runSkillsSequentially(CopilotInvestigation investigation, Long userId,
                                        List<String> skillKeys) {
        for (String skillKey : skillKeys) {
            try {
                log.info("[Copilot] Running skill '{}' for investigation #{}", skillKey, investigation.getId());
                var skillOpt = skillService.getSkillByKey(userId, skillKey);
                if (skillOpt.isEmpty()) {
                    log.warn("[Copilot] Skill '{}' not found for user {} — skipping", skillKey, userId);
                    continue;
                }

                if (orchestratorService.isSkillAlreadyInvoked(investigation, skillKey)) {
                    log.debug("[Copilot] Skipping already-invoked skill: {}", skillKey);
                    continue;
                }

                String skillPrompt = skillService.buildSkillPrompt(skillOpt.get());
                String context = orchestratorService.buildSkillPrompt(skillPrompt, investigation);
                log.info("[Copilot] Calling AI for skill '{}' (prompt {}chars)", skillKey, context.length());
                String rawResponse = aiService.call(userId, skillPrompt, context);
                log.info("[Copilot] Skill '{}' raw response: {}", skillKey, rawResponse);

                AIResponse response = responseParser.parse(rawResponse);
                log.info("[Copilot] Skill '{}' parsed response type: {}", skillKey, response.getType());

                investigationService.recordSkillInvoked(investigation.getId(), skillKey, rawResponse);
                handleSkillResponse(response, investigation, userId);
                investigation = investigationService.getOrThrow(investigation.getId());

            } catch (Exception e) {
                log.error("[Copilot] Error running skill '{}': {}", skillKey, e.getMessage(), e);
            }
        }
    }

    private void handleSkillResponse(AIResponse response, CopilotInvestigation investigation, Long userId) {
        // Use instanceof — @JsonTypeInfo consumes 'type' as a discriminator and doesn't populate the field
        if (response instanceof FindingResponse finding) {
            var hypothesis = hypothesisService.createFromFinding(investigation.getId(), finding);
            if (finding.getRelationships() != null) {
                hypothesisService.evaluateRelationships(investigation.getId(),
                        hypothesis.getId(), finding.getRelationships());
            }
            log.info("[Copilot] FINDING: hypothesis '{}' created for investigation {}",
                    finding.getHypothesisLabel(), investigation.getId());
        } else if (response instanceof NeedsExpertResponse q) {
            String questionText = q.getQuestionText() != null ? q.getQuestionText()
                    : (q.getReasoning() != null ? q.getReasoning() : "Expert review required");
            hypothesisService.createAnomalyFlag(investigation.getId(), null, questionText, "WARNING");
            log.info("[Copilot] NEEDS_EXPERT: question queued for investigation {}", investigation.getId());
        } else if (response instanceof NeedsDataResponse nd) {
            log.info("[Copilot] NEEDS_DATA: {} on {} needed for investigation {}",
                    nd.getDataType(), nd.getTimeframe(), investigation.getId());
            // TODO: fetch tier 2/3 data and retry skill
        } else if (response instanceof InvalidatedResponse inv) {
            if (inv.getHypothesisId() != null) {
                hypothesisService.transitionState(inv.getHypothesisId(), "INVALIDATED",
                        inv.getInvalidationReason());
            }
            log.info("[Copilot] INVALIDATED: hypothesis {} — {}", inv.getHypothesisId(), inv.getInvalidationReason());
        } else {
            log.info("[Copilot] Skill returned unhandled type {}: {}", response.getClass().getSimpleName(), response.getReasoning());
        }
    }

    // ─── Full Advanced Elliott Analysis ─────────────────────────────────────────

    /**
     * Run the complete advanced Elliott analysis pipeline:
     * Wave counting → Confluence zones → Scenario scoring → Entry candidates → Hypothesis tracking.
     *
     * @param symbol          trading symbol e.g. INFY
     * @param primaryTimeframe  primary timeframe e.g. "daily"
     * @param timeframes      comma-separated list e.g. "weekly,daily"
     */
    @PostMapping("/full-elliott")
    public ResponseEntity<?> fullElliottAnalysis(
            @RequestParam String symbol,
            @RequestParam String primaryTimeframe,
            @RequestParam(defaultValue = "daily,weekly") String timeframes,
            @RequestParam(defaultValue = "false") boolean aiRecommend,
            Authentication auth) {

        List<String> tfList = Arrays.stream(timeframes.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toList());

        if (tfList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No valid timeframes provided"));
        }

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Instrument not found: " + symbol));
        }

        Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
        Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
        Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();
        List<String> orderedTfs = new ArrayList<>();

        for (String tf : tfList) {
            Interval interval = mapTimeframeToInterval(tf);
            if (interval == null) {
                log.warn("[AdvancedElliott] Unknown timeframe '{}' — skipping", tf);
                continue;
            }
            try {
                List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(symbol, instrument, interval);
                BarSeries series = zigzagService.getBarSeries(symbol, instrument, interval);
                List<ZigZagPoint> pivotsWithLtp = zigzagService.withLtpPivot(pivots, series);
                MarketStructureData msd = marketStructureService.analyse(pivotsWithLtp, tf);
                pivotsByTf.put(tf, pivotsWithLtp);
                structureByTf.put(tf, msd);
                seriesByTf.put(tf, series);
                orderedTfs.add(tf);
            } catch (Exception e) {
                log.warn("[AdvancedElliott] Failed to load data for {} {}: {}", symbol, tf, e.getMessage());
            }
        }

        if (orderedTfs.isEmpty()) {
            return ResponseEntity.internalServerError().body(Map.of("error", "No data loaded for any timeframe"));
        }

        String resolvedPrimaryTf = orderedTfs.contains(primaryTimeframe) ? primaryTimeframe : orderedTfs.get(0);

        try {
            AdvancedElliottAnalysisResult result = advancedElliottService.analyze(
                    seriesByTf, pivotsByTf, structureByTf, orderedTfs, symbol, resolvedPrimaryTf);

            // Run processing pipeline and replace raw promptSummary with deduplicated output
            if (result.getWaveAnalysis() != null) {
                BarSeries primarySeries = seriesByTf.get(resolvedPrimaryTf);
                double currentPrice = (primarySeries != null && primarySeries.getBarCount() > 0)
                        ? primarySeries.getLastBar().getClosePrice().doubleValue() : 0.0;
                com.dtech.kitecon.analysis.dto.ProcessAnalysisResponse processed =
                        analysisProcessService.process(result.getWaveAnalysis(), symbol, currentPrice, resolvedPrimaryTf);
                result.setPromptSummary(processed.getHumanReadable());

                // Optional AI recommendation pass
                if (aiRecommend) {
                    try {
                        Long userId = resolveUserId(auth);
                        String instructions = orchestratorService.buildScenarioEvaluatorInstructions();

                        // Build data: Elliott analysis (with indicator snapshot) + market structure
                        StringBuilder data = new StringBuilder();
                        data.append("=== SYMBOL: ").append(symbol).append(" ===\n");
                        data.append("=== TIMEFRAMES: ").append(String.join(",", orderedTfs)).append(" ===\n\n");
                        data.append(processed.getHumanReadable()).append("\n\n");

                        // Append market structure summary
                        for (Map.Entry<String, MarketStructureData> e : structureByTf.entrySet()) {
                            String msdText = e.getValue().toPromptSummary();
                            if (msdText != null && !msdText.isBlank()) {
                                data.append("=== MARKET STRUCTURE (").append(e.getKey()).append(") ===\n");
                                data.append(msdText).append("\n");
                            }
                        }

                        String rawAiResponse = aiService.call(userId, instructions, data.toString());
                        log.info("[FullElliott] AI recommendation raw response: {}", rawAiResponse);

                        Object parsedResponse = responseParser.parse(rawAiResponse);
                        result.setAiRecommendation(parsedResponse);
                    } catch (Exception aiEx) {
                        log.error("[FullElliott] AI recommendation failed for {}: {}", symbol, aiEx.getMessage(), aiEx);
                        result.setAiRecommendation(Map.of("error", aiEx.getMessage()));
                    }
                }
            }

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[AdvancedElliott] Analysis failed for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Run the full advanced Elliott analysis pipeline with second-pass filtering and AI verification.
     * First pass: wave counting → confluence → scenario scoring → entries
     * Second pass: scenario filtering → ranking → compression
     * AI pass: AI verification of filtered scenario set
     */
    @PostMapping("/full-elliott-verified")
    public ResponseEntity<?> fullElliottVerified(
            @RequestParam String symbol,
            @RequestParam String primaryTimeframe,
            @RequestParam(defaultValue = "daily,weekly") String timeframes,
            Authentication auth) {

        Long userId = resolveUserId(auth);

        List<String> tfList = Arrays.stream(timeframes.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toList());

        if (tfList.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No valid timeframes provided"));
        }

        Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(symbol, new String[]{"NSE"});
        if (instrument == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Instrument not found: " + symbol));
        }

        Map<String, List<ZigZagPoint>> pivotsByTf = new LinkedHashMap<>();
        Map<String, MarketStructureData> structureByTf = new LinkedHashMap<>();
        Map<String, BarSeries> seriesByTf = new LinkedHashMap<>();
        List<String> orderedTfs = new ArrayList<>();

        for (String tf : tfList) {
            Interval interval = mapTimeframeToInterval(tf);
            if (interval == null) continue;
            try {
                List<ZigZagPoint> pivots = zigzagService.getOrComputePivots(symbol, instrument, interval);
                MarketStructureData msd = marketStructureService.analyse(pivots, tf);
                BarSeries series = zigzagService.getBarSeries(symbol, instrument, interval);
                pivotsByTf.put(tf, pivots);
                structureByTf.put(tf, msd);
                seriesByTf.put(tf, series);
                orderedTfs.add(tf);
            } catch (Exception e) {
                log.warn("[FullElliottVerified] Failed to load data for {} {}: {}", symbol, tf, e.getMessage());
            }
        }

        if (orderedTfs.isEmpty()) {
            return ResponseEntity.internalServerError().body(Map.of("error", "No data loaded for any timeframe"));
        }

        String resolvedPrimaryTf = orderedTfs.contains(primaryTimeframe) ? primaryTimeframe : orderedTfs.get(0);

        try {
            AdvancedElliottAnalysisResult firstPass = advancedElliottService.analyze(
                    seriesByTf, pivotsByTf, structureByTf, orderedTfs, symbol, resolvedPrimaryTf);

            com.dtech.ta.elliott.VerifiedElliottResult result = elliottVerificationService.verify(
                    firstPass.getScoredScenarios(), symbol, resolvedPrimaryTf, userId,
                    com.dtech.elliott.advanced.scenario.filter.config.FilterConfig.defaults());

            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("[FullElliottVerified] Analysis failed for {}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }
}
