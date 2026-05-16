package com.dtech.aitrader;

import com.dtech.aitrader.service.WalkForwardAiSimService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;

/**
 * Walk-forward simulation test for DTB + AI gate on INFY.
 * Run with: mvn test -Dwalkforward.run=true -Dtest=WalkForwardAiSimTest
 */
@SpringBootTest
@Slf4j
class WalkForwardAiSimTest {
    @Autowired
    private WalkForwardAiSimService sim;

    @Test
    void runInfyLastYear() {
        // Skip unless explicitly enabled
        Assumptions.assumeTrue("true".equals(System.getProperty("walkforward.run")));

        log.info("Starting INFY walk-forward simulation...");

        var result = sim.run("INFY", LocalDate.of(2025, 5, 1), LocalDate.of(2026, 5, 12), 1L);

        log.info("=== INFY Walk-Forward Results ===");
        log.info("Total signals: {}", result.totalSignals());
        log.info("AI TRADE verdicts: {}", result.aiTrades());
        log.info("AI NO_TRADE verdicts: {}", result.aiNoTrades());
        log.info("AI total PnL%: {}", String.format("%.2f", result.aiTotalPnlPct()));
        log.info("Baseline PnL%: {}", String.format("%.2f", result.baselinePnlPct()));

        if (!result.trades().isEmpty()) {
            log.info("\nFirst 3 trades:");
            result.trades().stream().limit(3).forEach(t ->
                log.info("  {} {} {}: entry={} SL={} target={} exit={} PnL={}%% reasoning={}",
                    t.signalTime(), t.direction(), t.verdict(),
                    String.format("%.2f", t.entryPrice()),
                    String.format("%.2f", t.slPrice()),
                    String.format("%.2f", t.targetPrice()),
                    String.format("%.2f", t.exitPrice()),
                    String.format("%.2f", t.pnlPct()),
                    t.reasoning().substring(0, Math.min(100, t.reasoning().length())))
            );
        }

        System.out.println("\n=== INFY Walk-Forward Results ===");
        System.out.println("Total signals: " + result.totalSignals());
        System.out.println("AI TRADE verdicts: " + result.aiTrades());
        System.out.println("AI NO_TRADE verdicts: " + result.aiNoTrades());
        System.out.println("AI total PnL%: " + String.format("%.2f", result.aiTotalPnlPct()));
        System.out.println("Baseline PnL%: " + String.format("%.2f", result.baselinePnlPct()));

        if (!result.trades().isEmpty()) {
            System.out.println("\nFirst 3 trades:");
            result.trades().stream().limit(3).forEach(t ->
                System.out.println(String.format("  %s %s %s: entry=%.2f SL=%.2f target=%.2f exit=%.2f PnL=%.2f%% reasoning=%s",
                    t.signalTime(), t.direction(), t.verdict(), t.entryPrice(), t.slPrice(),
                    t.targetPrice(), t.exitPrice(), t.pnlPct(), t.reasoning().substring(0, Math.min(100, t.reasoning().length()))))
            );
        }
    }
}
