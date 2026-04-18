package com.dtech.kitecon.simulation;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private final TradeSimulationService simulationService;

    @PostMapping("/run")
    public ResponseEntity<?> run(
            @RequestParam String strategy,
            @RequestParam String timeframe,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam(defaultValue = "15") int stepMinutes,
            @RequestParam(defaultValue = "") String symbols
    ) {
        Instant fromInst = Instant.parse(from);
        Instant toInst = Instant.parse(to);
        List<String> symbolList = symbols.isEmpty()
                ? getDefaultUniverse(strategy)
                : List.of(symbols.split(","));
        var result = simulationService.run(strategy, symbolList, timeframe, fromInst, toInst, stepMinutes);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/step")
    public ResponseEntity<?> step() {
        return ResponseEntity.ok(simulationService.step());
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        var ctx = simulationService.getStatus();
        if (ctx == null) return ResponseEntity.ok(Map.of("status", "no_active_simulation"));
        return ResponseEntity.ok(Map.of(
                "currentTime", ctx.getClock().getCurrentTime(),
                "completed", ctx.getClock().isCompleted(),
                "steps", ctx.getTotalSteps(),
                "signals", ctx.getTotalSignalsGenerated(),
                "openPositions", ctx.getOpenPositions().size(),
                "closedPositions", ctx.getClosedPositions().size(),
                "wins", ctx.getWins(),
                "losses", ctx.getLosses(),
                "totalPnlPct", ctx.getTotalPnlPct()
        ));
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset() {
        simulationService.reset();
        return ResponseEntity.ok(Map.of("status", "reset"));
    }

    private List<String> getDefaultUniverse(String strategy) {
        return List.of("RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK", "SBIN", "BHARTIARTL",
                "BAJFINANCE", "HINDUNILVR", "ITC", "LT", "KOTAKBANK", "AXISBANK", "MARUTI", "TITAN",
                "SUNPHARMA", "TATAMOTORS", "NTPC", "WIPRO", "ADANIENT", "HCLTECH", "ULTRACEMCO",
                "ASIANPAINT", "BAJAJ-AUTO", "ONGC", "NESTLEIND", "JSWSTEEL", "TATASTEEL", "TECHM", "POWERGRID");
    }
}
