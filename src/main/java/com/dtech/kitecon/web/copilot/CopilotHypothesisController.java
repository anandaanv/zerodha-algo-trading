package com.dtech.kitecon.web.copilot;

import com.dtech.kitecon.auth.User;
import com.dtech.kitecon.auth.UserRepository;
import com.dtech.kitecon.data.copilot.CopilotHypothesis;
import com.dtech.kitecon.data.copilot.CopilotHypothesisRelationship;
import com.dtech.kitecon.service.copilot.CopilotHypothesisService;
import com.dtech.kitecon.service.copilot.CopilotTradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/hypotheses")
@RequiredArgsConstructor
public class CopilotHypothesisController {

    private final CopilotHypothesisService hypothesisService;
    private final CopilotTradeService tradeService;
    private final UserRepository userRepository;

    /** Get all active hypotheses for an investigation */
    @GetMapping("/investigation/{investigationId}")
    public ResponseEntity<List<CopilotHypothesis>> getHypotheses(@PathVariable Long investigationId) {
        return ResponseEntity.ok(hypothesisService.getAllHypotheses(investigationId));
    }

    /** Get hypotheses board — full data for the dashboard */
    @GetMapping("/board")
    public ResponseEntity<?> getBoard(@RequestParam Long investigationId) {
        List<CopilotHypothesis> all = hypothesisService.getAllHypotheses(investigationId);
        List<?> flags = hypothesisService.getUnacknowledgedFlags(investigationId);
        return ResponseEntity.ok(Map.of(
                "hypotheses", all,
                "unacknowledgedFlags", flags
        ));
    }

    /** Expert confirms a hypothesis as tradeable — creates trade from hypothesis */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<?> confirmHypothesis(Authentication auth,
                                                @PathVariable Long id,
                                                @RequestBody Map<String, Object> body) {
        String entryType = (String) body.getOrDefault("entryType", "ANTICIPATORY");
        boolean isOverride = Boolean.parseBoolean(body.getOrDefault("override", "false").toString());
        String overrideReason = (String) body.getOrDefault("overrideReason", null);
        String systemObjection = (String) body.getOrDefault("systemObjection", null);

        CopilotHypothesis hypothesis = hypothesisService.getOrThrow(id);
        var trade = tradeService.createTrade(hypothesis, entryType, isOverride, systemObjection, overrideReason);

        return ResponseEntity.ok(Map.of(
                "tradeId", trade.getId(),
                "hypothesisId", id,
                "entryType", entryType,
                "isOverride", isOverride,
                "sl", trade.getSl() != null ? trade.getSl() : "not set",
                "tp", trade.getTp() != null ? trade.getTp() : "not set"
        ));
    }

    /** Expert dismisses a hypothesis */
    @PostMapping("/{id}/dismiss")
    public ResponseEntity<?> dismissHypothesis(@PathVariable Long id,
                                                @RequestBody Map<String, String> body) {
        String reason = body.getOrDefault("reason", "Dismissed by expert");
        hypothesisService.transitionState(id, "INVALIDATED", reason);
        return ResponseEntity.ok(Map.of("status", "dismissed", "hypothesisId", id));
    }

    /** Get relationships for a hypothesis */
    @GetMapping("/{id}/relationships")
    public ResponseEntity<List<CopilotHypothesisRelationship>> getRelationships(@PathVariable Long id) {
        return ResponseEntity.ok(hypothesisService.getRelationships(id));
    }

    /** Acknowledge an anomaly flag */
    @PostMapping("/flags/{flagId}/acknowledge")
    public ResponseEntity<?> acknowledgeFlag(@PathVariable Long flagId,
                                              @RequestBody Map<String, String> body) {
        String action = body.getOrDefault("action", "ACCEPT");
        String notes = body.getOrDefault("notes", null);
        var flag = hypothesisService.acknowledgeFlag(flagId, action, notes);
        return ResponseEntity.ok(Map.of("status", "acknowledged", "flagId", flagId, "action", action));
    }
}
