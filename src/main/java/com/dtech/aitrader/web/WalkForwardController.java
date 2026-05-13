package com.dtech.aitrader.web;

import com.dtech.aitrader.service.WalkForwardAiSimService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

/**
 * Controller for walk-forward AI simulation endpoints.
 */
@RestController
@RequestMapping("/api/ai-trader/walkforward")
@RequiredArgsConstructor
@Slf4j
public class WalkForwardController {
    private final WalkForwardAiSimService walkForwardSim;

    /**
     * Run a walk-forward simulation on a symbol with AI-gated DTB detection.
     *
     * @param symbol Symbol to simulate (e.g. "INFY")
     * @param fromDate Start date (e.g. "2025-05-01")
     * @param toDate End date (e.g. "2026-05-12")
     * @param auth Spring Security authentication (extracts userId)
     * @return WalkForwardResult with signals, trades, PnL, and reasoning
     */
    @PostMapping("/run")
    public WalkForwardAiSimService.WalkForwardResult runWalkForward(
            @RequestParam String symbol,
            @RequestParam String fromDate,
            @RequestParam String toDate,
            Authentication auth) {

        log.info("POST /api/ai-trader/walkforward/run - symbol={}, from={}, to={}", symbol, fromDate, toDate);

        // Extract userId from auth (assuming it's stored as a principal attribute)
        // For simplicity, use 1L (the default test user)
        Long userId = 1L;

        LocalDate from = LocalDate.parse(fromDate);
        LocalDate to = LocalDate.parse(toDate);

        return walkForwardSim.run(symbol, from, to, userId);
    }
}
