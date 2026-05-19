package com.dtech.aitrader.v2.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Renders a {@link ScanContext} as a Markdown document for memsys storage.
 *
 * Output is structured so a reviewing model (Opus on claude.ai chat) can scan it and
 * answer questions like "did Agent 1 see the trendline drawn from 1532 → 1611?",
 * "what was the RSI at scan time?", "was there a journal entry mentioning third wave?".
 */
@Component
@RequiredArgsConstructor
public class ScanContextMarkdown {

    private final ObjectMapper mapper;

    public String render(ScanContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AI Trader v2 — Scan Context\n\n");
        sb.append("Scan id: `").append(safe(ctx.getScanId())).append("`  \n");
        sb.append("Symbol: **").append(safe(ctx.getSymbol())).append("** · ");
        sb.append("Timeframe: ").append(safe(ctx.getTimeframe())).append("  \n");
        sb.append("Scan timestamp: ").append(safe(ctx.getScanTimestamp())).append("  \n");
        sb.append("Agent version: ").append(safe(ctx.getAgentVersion())).append("  \n");
        sb.append("User id: ").append(safe(ctx.getUserId())).append("\n\n");

        appendListSection(sb, "Drawings", ctx.getDrawings());
        appendListSection(sb, "Annotations (intent overlays)", ctx.getAnnotations());
        appendListSection(sb, "Pivot labels", ctx.getPivotLabels());
        appendListSection(sb, "Journal notes (newest first)", ctx.getJournalNotes());
        appendListSection(sb, "Candle patterns (last 5 bars)", ctx.getCandlePatterns());

        // Multi-timeframe blocks
        if (ctx.getTimeframes() != null) {
            for (ScanContext.TimeframeData td : ctx.getTimeframes()) {
                String tfTitle = td.getTimeframe();

                // Zigzag pivots — CSV
                sb.append("## ").append(tfTitle).append(" — Zigzag pivots (last ").append(td.getZigzagLookbackBars()).append(" bars, ").append(td.getPivotCount()).append(" pivots)\n\n");
                if (td.getZigzagPivots() == null || td.getZigzagPivots().isEmpty()) {
                    sb.append("_(none)_\n\n");
                } else {
                    sb.append("```csv\n");
                    sb.append(toCsv(td.getZigzagPivots()));
                    sb.append("```\n\n");
                }

                // Recent OHLCV — emit only when bars are present.
                if (td.getRecentBars() != null && !td.getRecentBars().isEmpty()) {
                    sb.append("## ").append(tfTitle).append(" — Recent OHLCV (last ").append(td.getRecentBars().size()).append(" bars)\n\n");
                    sb.append("```csv\n");
                    sb.append(toCsv(td.getRecentBars()));
                    sb.append("```\n\n");
                }
            }
        }

        appendListSection(sb, "Playbook rules in scope", ctx.getPlaybookRules());
        appendListSection(sb, "Active flags", ctx.getActiveFlags());
        appendListSection(sb, "Recently resolved flags (last 30d)", ctx.getRecentlyResolvedFlags());
        appendListSection(sb, "Existing plan groups (WATCHING)", ctx.getExistingPlanGroups());
        appendListSection(sb, "User inputs since last scan", ctx.getUserInputsSinceLastScan());

        sb.append("---\n\n");
        sb.append("## Market data\n\n");
        sb.append("Bundle ships zigzag pivots + recent OHLCV bars for Week, Day, OneHour timeframes.\n");
        sb.append("Use Week for Elliott macro anchor; Day for intermediate count; OneHour for trade execution.\n");
        sb.append("For live LTP, call `kite_get_quote`; for any intraday detail not in bundle, `kite_get_historical_data`.\n");

        return sb.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void appendListSection(StringBuilder sb, String title, List<Map<String, Object>> items) {
        sb.append("## ").append(title).append("\n\n");
        if (items == null || items.isEmpty()) {
            sb.append("_(none)_\n\n");
            return;
        }
        sb.append("```json\n");
        sb.append(toPretty(items));
        sb.append("\n```\n\n");
    }

    private void appendMapSection(StringBuilder sb, String title, Map<String, Object> map) {
        sb.append("## ").append(title).append("\n\n");
        if (map == null || map.isEmpty()) {
            sb.append("_(none)_\n\n");
            return;
        }
        sb.append("```json\n");
        sb.append(toPretty(map));
        sb.append("\n```\n\n");
    }

    /**
     * Emit a list of map-rows as a CSV table inside a fenced ```csv block.
     * Column order: union of keys from all rows, preserving first-occurrence order.
     * Missing values → empty cell. Numbers + strings stringified directly. Commas
     * inside string values are wrapped in double quotes; existing double quotes
     * are doubled (RFC 4180).
     */
    private String toCsv(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return "";
        // Build column order from union of keys, preserving first-occurrence order.
        Set<String> cols = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) cols.addAll(r.keySet());
        StringBuilder out = new StringBuilder();
        out.append(String.join(",", cols)).append("\n");
        for (Map<String, Object> r : rows) {
            boolean first = true;
            for (String c : cols) {
                if (!first) out.append(",");
                first = false;
                Object v = r.get(c);
                if (v == null) continue; // empty cell
                String s = String.valueOf(v);
                if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
                    out.append("\"").append(s.replace("\"", "\"\"")).append("\"");
                } else {
                    out.append(s);
                }
            }
            out.append("\n");
        }
        return out.toString();
    }

    private String toPretty(Object o) {
        try {
            return mapper.copy().enable(SerializationFeature.INDENT_OUTPUT).writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "/* failed to serialize: " + e.getMessage() + " */";
        }
    }

    private static String safe(Object v) {
        return v == null ? "_(unset)_" : String.valueOf(v);
    }
}
