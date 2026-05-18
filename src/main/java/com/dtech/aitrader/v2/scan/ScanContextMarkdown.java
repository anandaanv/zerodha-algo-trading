package com.dtech.aitrader.v2.scan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

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
        appendMapSection(sb, "Last-bar indicators", ctx.getIndicators());
        appendListSection(sb, "Playbook rules in scope", ctx.getPlaybookRules());
        appendListSection(sb, "Active flags", ctx.getActiveFlags());
        appendListSection(sb, "Recently resolved flags (last 30d)", ctx.getRecentlyResolvedFlags());
        appendListSection(sb, "Existing plan groups (WATCHING)", ctx.getExistingPlanGroups());
        appendListSection(sb, "User inputs since last scan", ctx.getUserInputsSinceLastScan());
        appendListSection(sb, "OHLC bars", ctx.getOhlcBars());

        if (ctx.getRenderedPromptText() != null && !ctx.getRenderedPromptText().isBlank()) {
            sb.append("---\n\n## Rendered prompt sent to Agent 1\n\n```\n");
            sb.append(ctx.getRenderedPromptText());
            if (!ctx.getRenderedPromptText().endsWith("\n")) sb.append("\n");
            sb.append("```\n");
        }

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
