package com.dtech.aitrader.v2.web;

import com.dtech.aitrader.data.PlanGroup;
import com.dtech.aitrader.data.PlanGroupState;
import com.dtech.aitrader.data.WatchTrade;
import com.dtech.aitrader.repository.PlanGroupRepository;
import com.dtech.aitrader.repository.WatchTradeRepository;
import com.dtech.aitrader.v2.batch.NightlyBundleDumpService;
import com.dtech.aitrader.v2.orchestrator.OrchestratorV2Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints to fire an Agent 1 scan and read back the resulting plan_groups + branches.
 * Browser-friendly so the trader can hit them from /ai-trader during the build phase.
 */
@RestController
@RequestMapping("/api/ai-trader-v2")
@RequiredArgsConstructor
@Slf4j
public class AiTraderV2Controller {

    private final OrchestratorV2Service orchestrator;
    private final NightlyBundleDumpService nightlyBatch;
    private final PlanGroupRepository planGroupRepository;
    private final WatchTradeRepository watchTradeRepository;

    @PostMapping("/scan")
    public ResponseEntity<?> scan(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "OneHour") String timeframe,
            @RequestParam(required = false) String tabUuid,
            Authentication auth) {
        Long userId = extractUserId(auth);
        try {
            OrchestratorV2Service.ScanResult result = orchestrator.scan(userId, symbol, timeframe, tabUuid);
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("v2 scan failed for symbol={}: {}", symbol, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Fires the nightly bundle-dump batch on demand. Same code path as the scheduled cron;
     * useful for testing / running a fresh batch before the next 22:00 IST cycle.
     */
    @PostMapping("/batch/run")
    public ResponseEntity<?> runBatch(
            @RequestParam(required = false) String symbols,
            Authentication auth) {
        try {
            NightlyBundleDumpService.Summary summary = nightlyBatch.runManual(symbols);
            return ResponseEntity.ok(summary);
        } catch (Exception e) {
            log.error("manual v2 batch fire failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/plan-groups")
    public ResponseEntity<?> listPlanGroups(
            @RequestParam(required = false) String symbol,
            @RequestParam(required = false) String state,
            Authentication auth) {
        Long userId = extractUserId(auth);
        List<PlanGroup> rows;
        if (symbol != null && state != null) {
            rows = planGroupRepository.findByUserIdAndSymbolAndState(
                    userId, symbol.toUpperCase(), PlanGroupState.valueOf(state.toUpperCase()));
        } else if (symbol != null) {
            rows = planGroupRepository.findByUserIdAndSymbolAndState(
                    userId, symbol.toUpperCase(), PlanGroupState.WATCHING);
        } else if (state != null) {
            rows = planGroupRepository.findByUserIdAndStateOrderByUpdatedAtDesc(
                    userId, PlanGroupState.valueOf(state.toUpperCase()));
        } else {
            rows = planGroupRepository.findByUserIdAndStateOrderByUpdatedAtDesc(userId, PlanGroupState.WATCHING);
        }
        return ResponseEntity.ok(rows.stream().map(this::toDto).toList());
    }

    @GetMapping("/plan-groups/{id}")
    public ResponseEntity<?> getPlanGroup(@PathVariable Long id, Authentication auth) {
        Long userId = extractUserId(auth);
        return planGroupRepository.findById(id)
                .filter(pg -> pg.getUserId().equals(userId))
                .map(pg -> {
                    Map<String, Object> dto = toDto(pg);
                    List<WatchTrade> branches = watchTradeRepository.findAll().stream()
                            .filter(wt -> id.equals(wt.getPlanGroupId()))
                            .toList();
                    dto.put("branches", branches);
                    return ResponseEntity.ok(dto);
                })
                .orElseGet(() -> ResponseEntity.status(404).body(null));
    }

    private Map<String, Object> toDto(PlanGroup pg) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", pg.getId());
        dto.put("symbol", pg.getSymbol());
        dto.put("timeframe", pg.getTimeframe());
        dto.put("state", pg.getState());
        dto.put("underlying_hypothesis", pg.getUnderlyingHypothesis());
        dto.put("structural_validation", pg.getStructuralValidation());
        dto.put("decision_zone", Map.of(
                "low", pg.getDecisionZoneLow(),
                "high", pg.getDecisionZoneHigh(),
                "rationale", pg.getDecisionZoneRationale()
        ));
        dto.put("valid_until", pg.getValidUntil());
        dto.put("created_at", pg.getCreatedAt());
        dto.put("agent_version", pg.getAgentVersion());
        return dto;
    }

    private Long extractUserId(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new IllegalStateException("User not authenticated");
        }
        return 1L; // matches existing pattern in AiLevelsController
    }
}
