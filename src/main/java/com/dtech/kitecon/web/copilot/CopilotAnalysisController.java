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

    private final CopilotAnalysisFacade analysisFacade;
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
        CopilotInvestigation investigation = analysisFacade.createOrReuseInvestigation(userId, body);
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
            observations = analysisFacade.runScanPhase(investigation, userId);
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
                analysisFacade.runReasonPhase(investigation, userId, reasonRequest);
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
            Interval interval = analysisFacade.mapTimeframeToInterval(tf);
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
                if (processed.getActivePatterns() != null) {
                    result.getWaveAnalysis().setAllPatterns(processed.getActivePatterns());
                }

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
            Interval interval = analysisFacade.mapTimeframeToInterval(tf);
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
