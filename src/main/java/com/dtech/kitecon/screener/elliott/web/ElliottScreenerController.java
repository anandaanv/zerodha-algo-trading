package com.dtech.kitecon.screener.elliott.web;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.screener.elliott.dto.*;
import com.dtech.kitecon.screener.elliott.entity.SuggestionChartLayout;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRunRepository;
import com.dtech.kitecon.screener.elliott.repository.SuggestionChartLayoutRepository;
import com.dtech.kitecon.screener.elliott.repository.ElliottTradeSuggestionRepository;
import com.dtech.kitecon.screener.elliott.repository.ElliottScreenerRepository;
import com.dtech.kitecon.screener.elliott.service.ElliottScreenerService;
import com.dtech.kitecon.screener.elliott.service.SuggestionChartLayoutService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/elliott-screener")
@RequiredArgsConstructor
@Slf4j
public class ElliottScreenerController {

    private final ElliottScreenerService screenerService;
    private final ElliottScreenerRunRepository runRepository;
    private final SuggestionChartLayoutRepository layoutRepository;
    private final ObjectMapper objectMapper;
    private final UserRepository userRepository;
    private final ElliottTradeSuggestionRepository suggestionRepository;
    private final ElliottScreenerRepository screenerRepository;
    private final SuggestionChartLayoutService layoutService;

    @GetMapping
    public ResponseEntity<?> list(Authentication auth) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(screenerService.listScreeners(userId));
    }

    @PostMapping
    public ResponseEntity<?> create(Authentication auth, @RequestBody ElliottScreenerRequest request) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(screenerService.createScreener(userId, request));
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> get(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(screenerService.getScreener(userId, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(Authentication auth, @PathVariable Long id,
                                     @RequestBody ElliottScreenerRequest request) {
        Long userId = resolveUserId(auth);
        return ResponseEntity.ok(screenerService.updateScreener(userId, id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        screenerService.deleteScreener(userId, id);
        return ResponseEntity.ok(Map.of("status", "disabled"));
    }

    @PostMapping("/{id}/run")
    public ResponseEntity<?> triggerRun(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        ElliottScreenerRunResponse response = screenerService.triggerNow(userId, id);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/runs")
    public ResponseEntity<?> getRuns(Authentication auth, @PathVariable Long id) {
        Long userId = resolveUserId(auth);
        List<ElliottScreenerRunResponse> runs = runRepository
                .findTop10ByScreenerIdOrderByStartedAtDesc(id)
                .stream()
                .map(ElliottScreenerRunResponse::from)
                .toList();
        return ResponseEntity.ok(runs);
    }

    @GetMapping("/suggestions/{suggestionId}/chart-layouts")
    public ResponseEntity<?> getSuggestionChartLayouts(
            @PathVariable Long suggestionId,
            Authentication auth) {
        Long userId = resolveUserId(auth);
        List<SuggestionChartLayout> layouts = layoutRepository
                .findBySuggestionIdOrderByTabOrder(suggestionId);

        List<Map<String, Object>> response = layouts.stream().map(layout -> {
            Map<String, Object> dto = new java.util.LinkedHashMap<>();
            dto.put("id", layout.getId());
            dto.put("suggestionId", layout.getSuggestionId());
            dto.put("timeframe", layout.getTimeframe());
            dto.put("tabOrder", layout.getTabOrder());

            try {
                Map<String, Object> overlays = layout.getOverlaysJson() != null
                        ? objectMapper.readValue(layout.getOverlaysJson(), Map.class)
                        : new java.util.LinkedHashMap<>();
                dto.put("overlays", overlays);
            } catch (Exception e) {
                log.warn("Failed to parse overlays_json for layout {}: {}", layout.getId(), e.getMessage());
                dto.put("overlays", new java.util.LinkedHashMap<>());
            }

            return dto;
        }).toList();

        return ResponseEntity.ok(response);
    }

    @PostMapping("/suggestions/{suggestionId}/generate-charts")
    public ResponseEntity<?> generateSuggestionCharts(
            @PathVariable Long suggestionId,
            Authentication auth) {
        try {
            // Load suggestion
            var suggestion = suggestionRepository.findById(suggestionId).orElse(null);
            if (suggestion == null) {
                return ResponseEntity.notFound().build();
            }

            // If allTimeframes or primaryTimeframe is null/blank, fall back to the screener's timeframes
            if (suggestion.getAllTimeframes() == null || suggestion.getAllTimeframes().isBlank()
                    || suggestion.getPrimaryTimeframe() == null || suggestion.getPrimaryTimeframe().isBlank()) {
                var screener = screenerRepository.findById(suggestion.getScreenerId()).orElse(null);
                if (screener != null) {
                    if (suggestion.getAllTimeframes() == null || suggestion.getAllTimeframes().isBlank()) {
                        suggestion.setAllTimeframes(screener.getTimeframes());
                    }
                    if (suggestion.getPrimaryTimeframe() == null || suggestion.getPrimaryTimeframe().isBlank()) {
                        suggestion.setPrimaryTimeframe(
                            screener.getPrimaryTimeframe() != null
                                ? screener.getPrimaryTimeframe()
                                : screener.getTimeframes().split(",")[0].trim()
                        );
                    }
                }
            }

            // Re-run Elliott analysis pipeline and generate layouts with real data
            screenerService.regenerateLayouts(suggestion);

            long count = layoutRepository.findBySuggestionIdOrderByTabOrder(suggestionId).size();
            return ResponseEntity.ok(Map.of("layoutsCreated", count));
        } catch (Exception e) {
            log.error("Failed to generate chart layouts for suggestion {}: {}", suggestionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    private Long resolveUserId(Authentication auth) {
        String username = auth.getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElseThrow(() -> new IllegalStateException("User not found: " + username));
    }
}
