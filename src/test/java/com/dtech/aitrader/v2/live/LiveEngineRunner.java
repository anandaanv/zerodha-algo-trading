package com.dtech.aitrader.v2.live;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.FiresOn;
import com.dtech.aitrader.v2.rules.MultiPassEngine;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.scancontext.ScanContextParser;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Live engine runner — reads the /tmp/bundle-*.md scan-context files (written by today's
 * /api/ai-trader-v2/batch/run) and runs the multi-pass engine. Reports per-stock firings.
 *
 * <p>Gated by env {@code RUN_LIVE_ENGINE=1} so it doesn't fire during regular test runs (would
 * need /tmp files and Spring context).
 */
@SpringBootTest(classes = com.dtech.kitecon.KiteconApplication.class)
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_ENGINE", matches = "1")
class LiveEngineRunner {

    @Autowired
    private MultiPassEngine engine;

    @Autowired
    private List<Rule> allRules;

    @Test
    void grade_three_stocks_from_tmp_bundles() throws Exception {
        runOne("RELIANCE", "/tmp/bundle-reliance.md");
        runOne("ICICIBANK", "/tmp/bundle-icicibank.md");
        runOne("TCS", "/tmp/bundle-tcs.md");
    }

    private void runOne(String symbol, String path) throws Exception {
        String body = Files.readString(Path.of(path));
        ScanContextParser.ParsedContext parsed = ScanContextParser.parse(body);
        Map<String, List<MarketStructurePoint>> byTf = parsed.getPivotsByTf();
        List<MarketStructurePoint> weekly = byTf.get("Week");
        if (weekly == null) weekly = List.of();

        SymbolContext ctx = SymbolContext.builder()
                .symbol(symbol)
                .asOf(LocalDate.of(2026, 5, 25))
                .tf("Week")
                .pivots(weekly)
                .pivotsByTf(byTf)
                .annotations(parsed.getAnnotations())
                .build();

        List<Firing> firings = engine.run(ctx, allRules);

        System.out.println("\n══════════════════════════════════════════════════════════════════════");
        System.out.println(" ENGINE OUTPUT — " + symbol + " — " + firings.size() + " firings");
        System.out.println("══════════════════════════════════════════════════════════════════════");
        System.out.println("Pivots: Week=" + weekly.size()
                + ", Day=" + byTf.getOrDefault("Day", List.of()).size()
                + ", Hr=" + byTf.getOrDefault("OneHour", List.of()).size()
                + ", Annotations=" + parsed.getAnnotations().size());

        // Group by family then ruleId then firesOn.
        for (Family fam : Family.values()) {
            List<Firing> byFam = firings.stream().filter(f -> f.getFamily() == fam).toList();
            if (byFam.isEmpty()) continue;
            System.out.println("\n[" + fam + "] " + byFam.size() + " firings");
            byFam.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            Firing::getRuleId, java.util.stream.Collectors.counting()))
                    .entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .forEach(e -> System.out.println("    " + e.getKey() + ": " + e.getValue()));
        }

        // Detail block: VERDICT + WATCH firings (Pass-6 outputs).
        List<Firing> verdicts = firings.stream()
                .filter(f -> f.getFiresOn() == FiresOn.VERDICT
                          || f.getFiresOn() == FiresOn.WATCH)
                .toList();
        if (!verdicts.isEmpty()) {
            System.out.println("\n-- VERDICT / WATCH firings --");
            for (Firing v : verdicts) {
                System.out.println("  " + v.getRuleId() + " " + v.getFiresOn()
                        + " bias=" + v.getBias()
                        + " trig=" + v.getTriggerPrice()
                        + " inv=" + v.getInvalidationPrice()
                        + " tgt=" + v.getTargetPrice());
            }
        }

        // Detail: signature firings (ADMITTED/INVALIDATED/PENDING).
        List<Firing> sigFirings = firings.stream()
                .filter(f -> "EW_SIGNATURE_EVALUATION".equals(f.getRuleId()))
                .toList();
        if (!sigFirings.isEmpty()) {
            System.out.println("\n-- EW signature evaluations --");
            for (Firing sig : sigFirings) {
                Map<String, Object> p = sig.getPayload();
                System.out.println("  " + p.get("form_name") + ": " + p.get("admission_state"));
            }
        }

        // Detail: exhaustion-at-target tilts.
        List<Firing> tilts = firings.stream()
                .filter(f -> "EW_EXHAUSTION_AT_TARGET".equals(f.getRuleId()))
                .toList();
        if (!tilts.isEmpty()) {
            System.out.println("\n-- exhaustion-at-target tilts --");
            for (Firing t : tilts) {
                Map<String, Object> p = t.getPayload();
                System.out.println("  tilt=" + p.get("tilt_direction")
                        + " score=" + p.get("tilt_score")
                        + " target=" + p.get("target_level"));
            }
        }

        // Detail: pattern firings.
        List<Firing> patternFirings = firings.stream()
                .filter(f -> f.getFamily() == Family.PATTERN)
                .toList();
        System.out.println("\n-- pattern firings: " + patternFirings.size());
        for (Firing pf : patternFirings) {
            Map<String, Object> p = pf.getPayload();
            System.out.println("  " + pf.getRuleId() + " status=" + p.get("status")
                    + " completion=" + p.get("completion_pct")
                    + " bias=" + p.get("bias"));
        }
    }
}
