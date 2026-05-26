package com.dtech.aitrader.v2.live;

import com.dtech.aitrader.v2.rules.Family;
import com.dtech.aitrader.v2.rules.Firing;
import com.dtech.aitrader.v2.rules.MultiPassEngine;
import com.dtech.aitrader.v2.rules.Rule;
import com.dtech.aitrader.v2.rules.SymbolContext;
import com.dtech.aitrader.v2.rules.patterns.dataload.PatternContextAttacher;
import com.dtech.aitrader.v2.rules.scancontext.ScanContextParser;
import com.dtech.kitecon.service.copilot.dto.MarketStructurePoint;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pattern-focused live scan — reads existing {@code /tmp/bundle-<symbol>.md} scan-contexts,
 * runs the full engine for each, filters to {@code Family.PATTERN} firings, and emits a
 * JSON summary to stdout + {@code /tmp/pattern-scan-output.json}.
 *
 * <p>Used to capture working pattern detections on real data per owner direction
 * {@code 4a322dbe} — "build patterns on zigzag, capture real-data test-cases, THEN re-platform
 * to candles." Step 2 of that sequence.
 *
 * <p>Stock list comes from env {@code PATTERN_SCAN_STOCKS} (CSV of UPPERCASE symbols), defaulting
 * to RELIANCE,ICICIBANK,TCS,HDFCBANK,INFY,MARUTI,SBIN,AXISBANK,TATASTEEL,SUNPHARMA. Pattern TF
 * defaults to "Day" and can be overridden with {@code PATTERN_SCAN_TF}.
 *
 * <p>Gated by env {@code RUN_LIVE_ENGINE=1} (matches {@link LiveEngineRunner}).
 */
@SpringBootTest(classes = com.dtech.kitecon.KiteconApplication.class)
@ActiveProfiles("integration")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_ENGINE", matches = "1")
class PatternFnoScanRunner {

    private static final String DEFAULT_STOCKS =
            "RELIANCE,ICICIBANK,TCS,HDFCBANK,INFY,MARUTI,SBIN,AXISBANK,TATASTEEL,SUNPHARMA";
    /** "zigzag" (default) or "candle" — pattern pivot substrate per owner direction 4a322dbe. */
    private static final String SUBSTRATE_ENV = "PATTERN_SUBSTRATE";

    @Autowired private MultiPassEngine engine;
    @Autowired private List<Rule> allRules;
    @Autowired private PatternContextAttacher patternContextAttacher;

    @Test
    void scan_pattern_firings_across_fno_stocks() throws Exception {
        String stocksEnv = System.getenv().getOrDefault("PATTERN_SCAN_STOCKS", DEFAULT_STOCKS);
        String patternTf = System.getenv().getOrDefault("PATTERN_SCAN_TF", "Day");
        String substrate = System.getenv().getOrDefault(SUBSTRATE_ENV, "zigzag");
        List<String> symbols = List.of(stocksEnv.split(","));

        Map<String, Object> overallResult = new LinkedHashMap<>();
        overallResult.put("pattern_tf", patternTf);
        overallResult.put("substrate", substrate);
        overallResult.put("scanned_at", java.time.Instant.now().toString());
        overallResult.put("stocks", new ArrayList<>());

        for (String sym : symbols) {
            String trimmed = sym.trim();
            if (trimmed.isEmpty()) continue;
            Map<String, Object> entry = runOne(trimmed, patternTf, substrate);
            ((List<Map<String, Object>>) overallResult.get("stocks")).add(entry);
        }

        // Write structured JSON for downstream summarisation.
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(overallResult);
        Path out = Path.of("/tmp/pattern-scan-output.json");
        Files.writeString(out, json);
        System.out.println("\n══════════════════════════════════════════════════════════════════════");
        System.out.println(" PATTERN SCAN COMPLETE — output written to " + out);
        System.out.println("══════════════════════════════════════════════════════════════════════");
        System.out.println(json);
    }

    private Map<String, Object> runOne(String symbol, String patternTf, String substrate) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("symbol", symbol);
        entry.put("pattern_tf", patternTf);
        entry.put("substrate", substrate);

        Path bundlePath = Path.of("/tmp/bundle-" + symbol.toLowerCase() + ".md");
        if (!Files.exists(bundlePath)) {
            entry.put("status", "no_bundle");
            entry.put("bundle_path", bundlePath.toString());
            return entry;
        }

        try {
            String body = Files.readString(bundlePath);
            ScanContextParser.ParsedContext parsed = ScanContextParser.parse(body);
            Map<String, List<MarketStructurePoint>> byTf = parsed.getPivotsByTf();
            List<MarketStructurePoint> weekly =
                    byTf.getOrDefault("Week", List.of());

            SymbolContext ctx = SymbolContext.builder()
                    .symbol(symbol)
                    .asOf(LocalDate.now())
                    .tf("Week")
                    .pivots(weekly)
                    .pivotsByTf(byTf)
                    .annotations(parsed.getAnnotations())
                    .build();

            // Attach pattern-side data per substrate choice (owner direction 4a322dbe).
            ctx = "candle".equalsIgnoreCase(substrate)
                    ? patternContextAttacher.attachWithCandleSwings(ctx, patternTf)
                    : patternContextAttacher.attach(ctx, patternTf);

            List<Firing> firings = engine.run(ctx, allRules);

            List<Firing> patternFirings = firings.stream()
                    .filter(f -> f.getFamily() == Family.PATTERN)
                    .toList();

            // Per-pattern grouping.
            Map<String, List<Map<String, Object>>> byRule = new LinkedHashMap<>();
            for (Firing pf : patternFirings) {
                Map<String, Object> row = new LinkedHashMap<>();
                Map<String, Object> p = pf.getPayload();
                row.put("status", p == null ? null : p.get("status"));
                row.put("completion_pct", p == null ? null : p.get("completion_pct"));
                row.put("bias", p == null ? null : p.get("bias"));
                // Pattern-family-specific extras (best-effort — only include if present).
                copyIfPresent(p, row,
                        "channel_direction", "channel_state",
                        "wedge_type", "wedge_state", "confirmed_direction",
                        "triangle_type",
                        "range_state",
                        "pattern_type", "pole_direction", "consolidation_state",
                        "trigger_price", "invalidation_price", "target_price",
                        "rect_height", "rect_height_atr",
                        "pole_height", "pole_pct_move",
                        "upper_line_at_now", "lower_line_at_now",
                        "upper_touches", "lower_touches",
                        "span_start_idx", "span_end_idx",
                        "span_bars", "consolidation_bars");
                byRule.computeIfAbsent(pf.getRuleId(), k -> new ArrayList<>()).add(row);
            }

            entry.put("status", "ok");
            entry.put("pivots_week", weekly.size());
            entry.put("pivots_day", byTf.getOrDefault("Day", List.of()).size());
            entry.put("pivots_hour", byTf.getOrDefault("OneHour", List.of()).size());
            entry.put("total_firings", firings.size());
            entry.put("pattern_firings", patternFirings.size());
            entry.put("by_rule", byRule);
            return entry;
        } catch (Exception e) {
            entry.put("status", "error");
            entry.put("error", e.getMessage());
            return entry;
        }
    }

    private static void copyIfPresent(Map<String, Object> src, Map<String, Object> dst,
                                       String... keys) {
        if (src == null) return;
        for (String k : keys) {
            if (src.containsKey(k)) dst.put(k, src.get(k));
        }
    }
}
