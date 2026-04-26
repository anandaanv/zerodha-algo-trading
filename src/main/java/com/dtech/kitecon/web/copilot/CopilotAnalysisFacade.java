package com.dtech.kitecon.web.copilot;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.ta4j.core.BarSeries;

import java.util.*;

/**
 * Business logic for copilot analysis — investigation lifecycle, scan/reason phases,
 * market structure, skill orchestration. Extracted from CopilotAnalysisController.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CopilotAnalysisFacade {

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
    private final InstrumentRepository instrumentRepository;
    private final ChartLayoutRepository chartLayoutRepository;

    public CopilotInvestigation createOrReuseInvestigation(Long userId, Map<String, Object> body) {
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
    public List<CopilotObservation> runScanPhase(CopilotInvestigation investigation, Long userId) {
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
    public void runReasonPhase(CopilotInvestigation investigation, Long userId,
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
    public void fetchAndStoreMarketStructure(Long investigationId, String symbol, List<String> timeframes) {
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

    public Interval mapTimeframeToInterval(String tf) {
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

    public void runSkillsSequentially(CopilotInvestigation investigation, Long userId,
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

    public void handleSkillResponse(AIResponse response, CopilotInvestigation investigation, Long userId) {
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
}
