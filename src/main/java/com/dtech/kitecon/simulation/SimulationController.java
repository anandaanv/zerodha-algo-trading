package com.dtech.kitecon.simulation;

import com.dtech.algo.series.Interval;
import com.dtech.chartpattern.zigzag.ZigZagService;
import com.dtech.kitecon.data.Instrument;
import com.dtech.kitecon.elliott.ImpulseAnalysisCore;
import com.dtech.kitecon.repository.InstrumentRepository;
import com.dtech.kitecon.trade.service.TradeOrchestrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.ta4j.core.BarSeries;

import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/simulation")
@RequiredArgsConstructor
public class SimulationController {

    private final TradeSimulationService simulationService;
    private final ImpulseAnalysisCore analysisCore;
    private final ZigZagService zigZagService;
    private final InstrumentRepository instrumentRepository;
    private final TradeOrchestrationService orchestrationService;

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

    /**
     * Test impulse signal for any stock on current live data.
     * GET /api/simulation/test-signal?symbol=ITC&timeframe=OneHour
     * Add &placeOrder=true to actually trigger order placement via orchestration.
     */
    @PostMapping("/test-signal")
    public ResponseEntity<?> testSignal(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "OneHour") String timeframe,
            @RequestParam(defaultValue = "false") boolean placeOrder
    ) {
        try {
            Instrument instrument = instrumentRepository.findByTradingsymbolAndExchangeIn(
                    symbol, new String[]{"NSE"});
            if (instrument == null)
                return ResponseEntity.badRequest().body(Map.of("error", "Instrument not found: " + symbol));

            Interval interval = Interval.valueOf(timeframe);
            BarSeries barSeries = zigZagService.getBarSeries(symbol, instrument, interval);
            if (barSeries == null || barSeries.getBarCount() < 50)
                return ResponseEntity.badRequest().body(Map.of("error", "Insufficient data for " + symbol));

            var pivots = zigZagService.getOrComputePivots(symbol, instrument, interval);
            if (pivots.size() < 5)
                return ResponseEntity.badRequest().body(Map.of("error", "Too few pivots: " + pivots.size()));

            Map<Instant, Integer> tsToIdx = new HashMap<>();
            for (int i = 0; i < barSeries.getBarCount(); i++)
                tsToIdx.put(barSeries.getBar(i).getEndTime(), i);

            var result = analysisCore.analyze(
                    new ImpulseAnalysisCore.AnalysisInput(symbol, pivots, barSeries, tsToIdx, interval));

            if (result.isEmpty())
                return ResponseEntity.ok(Map.of(
                        "status", "no_signal",
                        "symbol", symbol,
                        "message", "No impulse detected above threshold"));

            var r = result.get();
            var response = new LinkedHashMap<String, Object>();
            response.put("status", "signal_detected");
            response.put("symbol", symbol);
            response.put("direction", r.direction() == 1 ? "LONG" : "SHORT");
            response.put("confidence", r.impulseConf());
            response.put("bullish", r.bullishConf());
            response.put("bearish", r.bearishConf());
            response.put("entryPrice", r.entryPrice());
            response.put("stopLoss", r.stopLossPrice());
            response.put("target", r.targetPrice());

            if (placeOrder) {
                var signal = r.toSignal(Instant.now(), timeframe);
                signal.setInstrumentToken(instrument.getInstrumentToken());
                orchestrationService.onEntryTriggered(signal);
                response.put("orderPlaced", true);
                response.put("message", "Signal created and order triggered via orchestration");
            } else {
                response.put("orderPlaced", false);
                response.put("message", "Dry run — add ?placeOrder=true to trigger order");
            }

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private List<String> getDefaultUniverse(String strategy) {
        return List.of("RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK", "SBIN", "BHARTIARTL",
                "BAJFINANCE", "HINDUNILVR", "ITC", "LT", "KOTAKBANK", "AXISBANK", "MARUTI", "TITAN",
                "SUNPHARMA", "TATAMOTORS", "NTPC", "WIPRO", "ADANIENT", "HCLTECH", "ULTRACEMCO",
                "ASIANPAINT", "BAJAJ-AUTO", "ONGC", "NESTLEIND", "JSWSTEEL", "TATASTEEL", "TECHM", "POWERGRID");
    }
}
