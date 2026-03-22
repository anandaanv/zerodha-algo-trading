package com.dtech.kitecon.web.copilot;

import com.dtech.kitecon.data.copilot.CopilotActiveTrade;
import com.dtech.kitecon.service.copilot.CopilotHypothesisService;
import com.dtech.kitecon.service.copilot.CopilotTradeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/trades")
@RequiredArgsConstructor
public class CopilotTradeController {

    private final CopilotTradeService tradeService;
    private final CopilotHypothesisService hypothesisService;

    /** Full dashboard data — hypotheses + active trades + override trades */
    @GetMapping("/dashboard")
    public ResponseEntity<?> getDashboard(@RequestParam Long investigationId) {
        return ResponseEntity.ok(Map.of(
                "activeTrades", tradeService.getActiveTrades(investigationId),
                "overrideTrades", tradeService.getOverrideTrades(),
                "hypotheses", hypothesisService.getAllHypotheses(investigationId),
                "unacknowledgedFlags", hypothesisService.getUnacknowledgedFlags(investigationId)
        ));
    }

    /** Mark trade as entered by expert (with actual price and size) */
    @PostMapping("/{id}/open")
    public ResponseEntity<CopilotActiveTrade> openTrade(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        BigDecimal entryPrice = new BigDecimal(body.get("entryPrice").toString());
        BigDecimal size = body.containsKey("size")
                ? new BigDecimal(body.get("size").toString())
                : BigDecimal.ONE;
        return ResponseEntity.ok(tradeService.enterTrade(id, entryPrice, size));
    }

    /** Mark trade as closed */
    @PostMapping("/{id}/close")
    public ResponseEntity<CopilotActiveTrade> closeTrade(@PathVariable Long id,
                                                          @RequestBody Map<String, Object> body) {
        BigDecimal closePrice = new BigDecimal(body.get("closePrice").toString());
        String outcome = (String) body.getOrDefault("outcome", "OPEN");
        String notes = (String) body.getOrDefault("notes", null);
        return ResponseEntity.ok(tradeService.closeTrade(id, closePrice, outcome, notes));
    }

    /** Log an expert override (expert entering against system objection) */
    @PostMapping("/{id}/override")
    public ResponseEntity<CopilotActiveTrade> logOverride(@PathVariable Long id,
                                                           @RequestBody Map<String, String> body) {
        String systemObjection = body.get("systemObjection");
        String overrideReason = body.get("overrideReason");
        return ResponseEntity.ok(tradeService.logOverride(id, systemObjection, overrideReason));
    }

    /** Get all override trades (for learning analytics — data captured, no UI in Phase 1) */
    @GetMapping("/overrides")
    public ResponseEntity<List<CopilotActiveTrade>> getOverrideTrades() {
        return ResponseEntity.ok(tradeService.getOverrideTrades());
    }
}
