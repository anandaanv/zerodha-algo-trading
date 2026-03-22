package com.dtech.kitecon.web.copilot;

import com.dtech.kitecon.data.copilot.CopilotAnomalyFlag;
import com.dtech.kitecon.repository.copilot.CopilotAnomalyFlagRepository;
import com.dtech.kitecon.service.copilot.CopilotHypothesisService;
import com.dtech.kitecon.service.copilot.CopilotMonitoringService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class CopilotMonitoringController {

    private final CopilotMonitoringService monitoringService;
    private final CopilotHypothesisService hypothesisService;
    private final CopilotAnomalyFlagRepository anomalyFlagRepository;

    /**
     * Triggered on each new candle close.
     * Runs confirmation skill checks for all active hypotheses in monitoring state.
     *
     * Body: { investigationId, symbol, timeframe, latestCandle, indicators }
     */
    @PostMapping("/candle-close")
    public ResponseEntity<?> candleClose(@RequestBody Map<String, Object> body) {
        Long investigationId = Long.valueOf(body.get("investigationId").toString());
        Long userId = Long.valueOf(body.getOrDefault("userId", "0").toString());
        String symbol = (String) body.get("symbol");
        String timeframe = (String) body.get("timeframe");
        String candleData = body.containsKey("latestCandle")
                ? body.get("latestCandle").toString()
                : body.toString();

        // Runs async — doesn't block the response
        monitoringService.processNewCandle(investigationId, userId, symbol, timeframe, candleData);

        return ResponseEntity.ok(Map.of(
                "status", "processing",
                "investigationId", investigationId,
                "message", "Candle close processing initiated asynchronously"
        ));
    }

    /** Get pending alerts for expert review */
    @GetMapping("/alerts")
    public ResponseEntity<?> getAlerts(@RequestParam Long investigationId) {
        List<CopilotAnomalyFlag> flags = hypothesisService.getUnacknowledgedFlags(investigationId);
        return ResponseEntity.ok(Map.of(
                "pendingAlerts", flags,
                "count", flags.size()
        ));
    }

    /** Expert acknowledges an alert */
    @PostMapping("/alerts/{id}/acknowledge")
    public ResponseEntity<?> acknowledgeAlert(@PathVariable Long id,
                                               @RequestBody Map<String, String> body) {
        String action = body.getOrDefault("action", "ACCEPT");
        String notes = body.getOrDefault("notes", null);
        var flag = hypothesisService.acknowledgeFlag(id, action, notes);
        return ResponseEntity.ok(Map.of("status", "acknowledged", "flagId", id, "action", action));
    }
}
