package com.dtech.aitrader.v2.scan;

import com.dtech.aitrader.annotation.dto.AnnotationBundleDto;
import com.dtech.aitrader.annotation.dto.DrawingAnnotationDto;
import com.dtech.aitrader.annotation.dto.JournalNoteDto;
import com.dtech.aitrader.annotation.service.AnnotationBundleBuilder;
import com.dtech.aitrader.data.PlanGroup;
import com.dtech.aitrader.repository.PlanGroupRepository;
import com.dtech.aitrader.data.PlanGroupState;
import com.dtech.chartdata.model.OhlcBarDTO;
import com.dtech.chartdata.service.ChartDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Assembles the input bundle for Agent 1 — produces the user-message string + a structured
 * {@link ScanContext} record so the orchestrator can also push the scan context to memsys.
 *
 * Memsys-only sections (`<playbook_rules>`, `<active_flags>`, `<recently_resolved_flags>`,
 * `<user_inputs_since_last_scan>`) are emitted as empty arrays until per-user memsys auth lands;
 * Agent 1's prompt tolerates this (treats them as "no preloaded context" and proceeds with
 * standard TA + the user's drawings/journal).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Agent1BundleBuilder {

    private static final int MAX_BARS = 250;
    private static final int CANDLE_PATTERN_LOOKBACK = 5;

    private final ChartDataService chartDataService;
    private final AnnotationBundleBuilder annotationBundleBuilder;
    private final PlanGroupRepository planGroupRepository;

    /** Build the bundle. Side-effect-free; orchestrator decides what to do with the result. */
    public Built build(Long userId, String symbol, String timeframe, String tabUuid, String agentVersion) {
        Instant scanTimestamp = Instant.now();
        String scanId = UUID.randomUUID().toString();

        // Fetch bars (last 250 of the requested timeframe).
        List<OhlcBarDTO> bars = chartDataService.getBars(symbol, timeframe, null, null, false);
        if (bars == null) bars = List.of();
        List<OhlcBarDTO> recentBars = bars.stream()
                .skip(Math.max(0, bars.size() - MAX_BARS))
                .collect(Collectors.toList());

        // Candle pattern tags on the last 5 bars.
        List<Map<String, Object>> candlePatterns = classifyLastN(recentBars, CANDLE_PATTERN_LOOKBACK);

        // Annotations + journal — from existing AnnotationBundleBuilder. Drawing geometry comes from
        // the saved drawings table (separately included as the `drawings` block). For the v2 bundle
        // we surface the structured annotation intents + journal notes via the bundle DTO.
        AnnotationBundleDto annBundle = (tabUuid != null && !tabUuid.isBlank())
                ? annotationBundleBuilder.buildForLevels(userId, symbol, tabUuid)
                : annotationBundleBuilder.buildForPattern(userId, symbol);

        List<Map<String, Object>> annotationsBlock = annBundle.getAnnotations() == null
                ? List.of()
                : annBundle.getAnnotations().stream().map(Agent1BundleBuilder::annotationToMap).toList();
        List<Map<String, Object>> journalBlock = annBundle.getJournalNotes() == null
                ? List.of()
                : annBundle.getJournalNotes().stream().map(Agent1BundleBuilder::journalToMap).toList();

        // Existing WATCHING plan_groups for (user, symbol) — what Agent 1 may UPDATE/SUPERSEDE/RETIRE.
        List<Map<String, Object>> existingGroups = planGroupRepository
                .findByUserIdAndSymbolAndState(userId, symbol, PlanGroupState.WATCHING)
                .stream().map(Agent1BundleBuilder::planGroupToMap).toList();

        // Memsys-only sections — empty until per-user memsys OAuth lands.
        List<Map<String, Object>> emptyList = List.of();
        Map<String, Object> emptyMap = Map.of();

        ScanContext ctx = ScanContext.builder()
                .scanId(scanId)
                .userId(userId)
                .symbol(symbol)
                .timeframe(timeframe)
                .scanTimestamp(scanTimestamp)
                .agentVersion(agentVersion)
                .drawings(emptyList) // TODO: fetch from user_chart_state once drawings extractor exposes structured points
                .annotations(annotationsBlock)
                .pivotLabels(emptyList) // TODO: separate pivot-label store; treat as empty for now
                .journalNotes(journalBlock)
                .ohlcBars(barsToList(recentBars))
                .indicators(emptyMap) // TODO: indicator snapshot — defer to next iteration
                .candlePatterns(candlePatterns)
                .playbookRules(emptyList) // memsys-blocked
                .activeFlags(emptyList) // memsys-blocked
                .recentlyResolvedFlags(emptyList) // memsys-blocked
                .existingPlanGroups(existingGroups)
                .userInputsSinceLastScan(emptyList) // memsys-blocked
                .build();

        String renderedPrompt = renderUserMessage(ctx);
        ctx.setRenderedPromptText(renderedPrompt);

        return new Built(ctx, renderedPrompt);
    }

    // ── render to <tagged> user-message string ───────────────────────

    private String renderUserMessage(ScanContext ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("<symbol>").append(ctx.getSymbol()).append("</symbol>\n");
        sb.append("<timeframe>").append(ctx.getTimeframe()).append("</timeframe>\n");
        sb.append("<scan_timestamp>").append(
                ctx.getScanTimestamp().atZone(ZoneId.of("Asia/Kolkata"))
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        ).append("</scan_timestamp>\n\n");

        appendBlock(sb, "price_data", ctx.getOhlcBars());
        appendBlock(sb, "indicators", ctx.getIndicators());
        appendBlock(sb, "drawings", ctx.getDrawings());
        appendBlock(sb, "labels", ctx.getPivotLabels());
        appendBlock(sb, "annotations", ctx.getAnnotations());
        appendBlock(sb, "journal_notes", ctx.getJournalNotes());
        appendBlock(sb, "candle_patterns_last_5", ctx.getCandlePatterns());
        appendBlock(sb, "playbook_rules", ctx.getPlaybookRules());
        appendBlock(sb, "active_flags", ctx.getActiveFlags());
        appendBlock(sb, "recently_resolved_flags", ctx.getRecentlyResolvedFlags());
        appendBlock(sb, "existing_plan_groups", ctx.getExistingPlanGroups());
        appendBlock(sb, "user_inputs_since_last_scan", ctx.getUserInputsSinceLastScan());

        return sb.toString();
    }

    private void appendBlock(StringBuilder sb, String tag, Object body) {
        sb.append("<").append(tag).append(">\n");
        if (body == null) {
            sb.append("[]\n");
        } else if (body instanceof Collection<?> c && c.isEmpty()) {
            sb.append("[]\n");
        } else if (body instanceof Map<?, ?> m && m.isEmpty()) {
            sb.append("{}\n");
        } else {
            sb.append(safeJson(body)).append("\n");
        }
        sb.append("</").append(tag).append(">\n\n");
    }

    private String safeJson(Object o) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(o);
        } catch (Exception e) {
            return "/* serialization failed: " + e.getMessage() + " */";
        }
    }

    // ── candle pattern classification (mirrors AiAnalysePromptBuilder.classifyCandle) ──

    private List<Map<String, Object>> classifyLastN(List<OhlcBarDTO> bars, int n) {
        if (bars.size() < n + 1) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (int offset = n - 1; offset >= 0; offset--) {
            int curIdx = bars.size() - 1 - offset;
            OhlcBarDTO prev = bars.get(curIdx - 1);
            OhlcBarDTO cur = bars.get(curIdx);
            List<String> tags = classifyCandle(prev, cur);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timestamp", Instant.ofEpochSecond(cur.getTime()).toString());
            row.put("patterns", tags);
            out.add(row);
        }
        return out;
    }

    private List<String> classifyCandle(OhlcBarDTO prev, OhlcBarDTO cur) {
        List<String> tags = new ArrayList<>();
        double bodyCur = Math.abs(cur.getClose() - cur.getOpen());
        double rangeCur = Math.max(1e-9, cur.getHigh() - cur.getLow());
        double upperWick = cur.getHigh() - Math.max(cur.getOpen(), cur.getClose());
        double lowerWick = Math.min(cur.getOpen(), cur.getClose()) - cur.getLow();
        boolean curGreen = cur.getClose() > cur.getOpen();
        boolean prevGreen = prev.getClose() > prev.getOpen();

        if (!curGreen && prevGreen
                && cur.getOpen() >= prev.getClose()
                && cur.getClose() <= prev.getOpen()) tags.add("bearish_engulfing");
        if (curGreen && !prevGreen
                && cur.getOpen() <= prev.getClose()
                && cur.getClose() >= prev.getOpen()) tags.add("bullish_engulfing");
        if (bodyCur < 0.35 * rangeCur && lowerWick > 1.8 * bodyCur && lowerWick > upperWick) tags.add("hammer");
        if (bodyCur < 0.35 * rangeCur && upperWick > 1.8 * bodyCur && upperWick > lowerWick) tags.add("shooting_star");
        if (bodyCur < 0.10 * rangeCur) tags.add("doji");
        if (cur.getHigh() < prev.getHigh() && cur.getLow() > prev.getLow()) tags.add("inside_bar");

        return tags;
    }

    // ── mappers ──────────────────────────────────────────────────────

    private List<Map<String, Object>> barsToList(List<OhlcBarDTO> bars) {
        List<Map<String, Object>> out = new ArrayList<>(bars.size());
        for (OhlcBarDTO b : bars) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("t", Instant.ofEpochSecond(b.getTime()).toString());
            row.put("o", b.getOpen());
            row.put("h", b.getHigh());
            row.put("l", b.getLow());
            row.put("c", b.getClose());
            row.put("v", b.getVolume());
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> annotationToMap(DrawingAnnotationDto a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "ann-" + a.getId());
        m.put("intent", a.getIntent());
        m.put("note", a.getNote());
        m.put("weight", a.getWeight());
        m.put("drawing_id", a.getDrawingId());
        m.put("interval", a.getInterval());
        m.put("params", a.getIntentParamsJson());
        m.put("geometry", a.getGeometryJson());
        return m;
    }

    private static Map<String, Object> journalToMap(JournalNoteDto n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "j-" + n.getId());
        m.put("note_date", n.getNoteDate() == null ? null : n.getNoteDate().toString());
        m.put("note", n.getNoteText());
        return m;
    }

    private static Map<String, Object> planGroupToMap(PlanGroup pg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(pg.getId()));
        m.put("state", pg.getState() == null ? null : pg.getState().name());
        m.put("underlying_hypothesis", pg.getUnderlyingHypothesis());
        m.put("decision_zone_low", pg.getDecisionZoneLow());
        m.put("decision_zone_high", pg.getDecisionZoneHigh());
        m.put("valid_until", pg.getValidUntil() == null ? null : pg.getValidUntil().toString());
        return m;
    }

    /** Result of bundle assembly — both structured context and rendered user-message. */
    public record Built(ScanContext scanContext, String userMessage) {}
}
