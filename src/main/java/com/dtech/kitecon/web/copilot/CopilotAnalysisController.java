package com.dtech.kitecon.web.copilot;

import com.dtech.chartpattern.zigzag.ZigZagPoint;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.data.ChartLayout;
import com.dtech.kitecon.data.copilot.CopilotInvestigation;
import com.dtech.kitecon.data.copilot.CopilotSkill;
import com.dtech.kitecon.repository.ChartLayoutRepository;
import com.dtech.kitecon.service.copilot.*;
import com.dtech.kitecon.service.copilot.dto.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API for triggering and managing Co-Pilot investigations.
 *
 * POST /api/analysis/trigger   — start an investigation when layout opens
 * POST /api/analysis/respond   — submit expert response to a NEEDS_EXPERT question
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
    private final CopilotAIService aiService;
    private final AIResponseParser responseParser;
    private final MarketStructureService marketStructureService;
    private final UserRepository userRepository;
    private final ChartLayoutRepository chartLayoutRepository;

    /**
     * Trigger an investigation when a layout opens or expert annotates.
     *
     * Body: { layoutId, symbol, drawingsJson, zigzagData (optional), timeframes[] }
     * Returns: { investigationId, hypotheses[], flags[] }
     */
    @PostMapping("/trigger")
    public ResponseEntity<?> triggerAnalysis(Authentication auth,
                                              @RequestBody Map<String, Object> body) {
        Long userId = resolveUserId(auth);
        Long layoutId = Long.valueOf(body.get("layoutId").toString());
        String symbol = (String) body.get("symbol");
        String drawingsJson = (String) body.getOrDefault("drawingsJson", null);
        String initiatedBy = (String) body.getOrDefault("initiatedBy", "LAYOUT_OPEN");
        boolean force = Boolean.parseBoolean(body.getOrDefault("force", "false").toString());

        @SuppressWarnings("unchecked")
        List<String> timeframes = (List<String>) body.getOrDefault("timeframes",
                List.of("daily", "1h", "15min"));

        // Get layout validity window
        int validityMinutes = chartLayoutRepository.findById(layoutId)
                .map(layout -> layout.getCopilotValidityMinutes() != null
                        ? layout.getCopilotValidityMinutes() : 60)
                .orElse(60);

        // Return existing active investigation unless force=true (manual re-trigger)
        if (!force) {
            Optional<CopilotInvestigation> existing = investigationService.getActiveInvestigation(layoutId, userId);
            if (existing.isPresent()) {
                return ResponseEntity.ok(Map.of(
                        "investigationId", existing.get().getId(),
                        "status", "existing",
                        "message", "Active investigation found. Use existing or wait for expiry.",
                        "hypotheses", hypothesisService.getAllHypotheses(existing.get().getId()),
                        "flags", hypothesisService.getUnacknowledgedFlags(existing.get().getId())
                ));
            }
        }

        // Start new investigation
        CopilotInvestigation investigation = investigationService.startInvestigation(
                layoutId, userId, symbol, String.join(",", timeframes), validityMinutes, initiatedBy);

        // Store drawings data
        if (drawingsJson != null) {
            investigationService.updateData(investigation.getId(), null, null, drawingsJson);
        }

        // Get previous investigation for context
        Optional<CopilotInvestigation> previous = investigationService.getLatestInvestigation(layoutId, userId);

        // Run orchestrator to determine which skills to invoke
        List<CopilotSkill> availableSkills = skillService.getAllSkillsForUser(userId);

        String orchestratorWarning = null;

        if (availableSkills.isEmpty()) {
            orchestratorWarning = "No skills configured. Go to /skills to create or seed skills.";
            log.warn("[Copilot] Investigation #{} — no skills for user {}", investigation.getId(), userId);
        } else {
            log.info("[Copilot] Investigation #{} — {} skill(s) available: {}", investigation.getId(),
                    availableSkills.size(),
                    availableSkills.stream().map(s -> s.getSkillKey()).toList());
            try {
                String orchestratorPrompt = orchestratorService.buildOrchestratorPrompt(
                        availableSkills, investigation, previous);
                log.info("[Copilot] Calling orchestrator AI for investigation #{}", investigation.getId());

                String rawResponse = aiService.call(userId, orchestratorPrompt,
                        "Determine which skills to invoke for this investigation.");
                log.info("[Copilot] Orchestrator raw response: {}", rawResponse);

                AIResponse response = responseParser.parse(rawResponse);

                if (response instanceof OrchestratorResponse orchResponse) {
                    log.info("[Copilot] Orchestrator selected skills: {} | rationale: {}",
                            orchResponse.getSkillsToInvoke(), orchResponse.getSelectionRationale());
                    List<String> skillsToRun = orchestratorService.filterCycleSkills(
                            orchResponse.getSkillsToInvoke(), investigation);
                    log.info("[Copilot] Skills to run after cycle filter: {}", skillsToRun);

                    if (!skillsToRun.isEmpty()) {
                        runSkillsSequentially(investigation, userId, skillsToRun);
                    } else {
                        log.warn("[Copilot] No skills to run — all already invoked or list empty");
                    }
                } else {
                    log.warn("[Copilot] Orchestrator did not return OrchestratorResponse, got: {}",
                            response.getClass().getSimpleName());
                }
            } catch (Exception e) {
                orchestratorWarning = e.getMessage();
                log.error("[Copilot] Orchestrator call failed for investigation #{}: {}",
                        investigation.getId(), e.getMessage(), e);
            }
        }

        // Reload investigation after skill runs
        investigation = investigationService.getOrThrow(investigation.getId());

        var result = new java.util.HashMap<String, Object>();
        result.put("investigationId", investigation.getId());
        result.put("status", "created");
        result.put("hypotheses", hypothesisService.getAllHypotheses(investigation.getId()));
        result.put("flags", hypothesisService.getUnacknowledgedFlags(investigation.getId()));
        if (orchestratorWarning != null) result.put("warning", orchestratorWarning);
        return ResponseEntity.ok(result);
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

    // ─── Internal helpers ─────────────────────────────────────────────────────

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
            hypothesisService.createAnomalyFlag(investigation.getId(), null,
                    q.getQuestionText(), "WARNING");
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

    private Long resolveUserId(Authentication auth) {
        return userRepository.findByUsername(auth.getName())
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + auth.getName()));
    }
}
