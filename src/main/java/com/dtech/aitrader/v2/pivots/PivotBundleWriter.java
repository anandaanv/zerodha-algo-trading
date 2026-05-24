package com.dtech.aitrader.v2.pivots;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.aitrader.v2.memsys.MemsysWriteResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persists one {@link PivotBundle} per symbol to memsys. Tag scheme per owner b5ffa13f:
 * {@code [ai-trader-v2, pivot-bundle, symbol-<SYM>, date-<YYYY-MM-DD>, asof-<YYYY-MM-DD>]}.
 *
 * <p>Body is JSON ({@code pivot-bundle-v1} schema) so downstream specialists can parse pivots
 * directly without re-detecting them. Each TF section: bar0, last_bar, bar_count, pivots[].
 *
 * <p>{@code force_new=true} everywhere — embedding-similarity dedup would otherwise collapse
 * similar pivot structures across symbols (same lesson from the v1 narrative run).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PivotBundleWriter {

    private static final ObjectMapper JSON = JsonMapper.builder()
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .build();

    private static final int MEMSYS_MAX_CONTENT_CHARS = 200_000;

    private final MemsysClient memsys;

    /** Write a pivot bundle. Returns the memsys memory id on success, {@code null} on failure. */
    public String write(PivotBundle bundle) {
        if (bundle == null) return null;
        String body;
        try {
            Map<String, Object> top = new LinkedHashMap<>();
            top.put("schema", "pivot-bundle-v1");
            top.put("symbol", bundle.getSymbol());
            top.put("date_label", bundle.getDateLabel());
            top.put("as_of_date", bundle.getAsOfDate());
            top.put("timeframes", bundle.getTimeframes());
            body = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(top);
        } catch (Exception e) {
            log.error("[pivot-bundle] {} JSON serialize failed: {}", bundle.getSymbol(), e.getMessage());
            return null;
        }
        if (body.length() > MEMSYS_MAX_CONTENT_CHARS) {
            log.warn("[pivot-bundle] {} body {} chars > {} cap; will truncate body but keep structure",
                    bundle.getSymbol(), body.length(), MEMSYS_MAX_CONTENT_CHARS);
            body = body.substring(0, MEMSYS_MAX_CONTENT_CHARS) + "\n…[truncated by PivotBundleWriter]";
        }

        List<String> tags = List.of(
                "ai-trader-v2",
                "pivot-bundle",
                "symbol-" + bundle.getSymbol(),
                "date-" + bundle.getDateLabel(),
                "asof-" + (bundle.getAsOfDate() == null ? bundle.getDateLabel() : bundle.getAsOfDate())
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("symbol", bundle.getSymbol());
        metadata.put("date_label", bundle.getDateLabel());
        metadata.put("as_of_date", bundle.getAsOfDate());
        metadata.put("timeframe_count", bundle.getTimeframes() == null ? 0 : bundle.getTimeframes().size());
        metadata.put("schema", "pivot-bundle-v1");

        try {
            MemsysWriteResult result = memsys.writeMemory(
                    bundle.getUserId(), body, "fact", tags, metadata,
                    /*parentId*/ null, /*supersedes*/ null,
                    /*forceNew*/ true, /*indexable*/ true, /*expiresAt*/ null);
            log.info("[pivot-bundle] wrote memsys id={} symbol={} tfs={} body_chars={} asof={}",
                    result.getId(), bundle.getSymbol(),
                    bundle.getTimeframes() == null ? 0 : bundle.getTimeframes().size(),
                    body.length(), bundle.getAsOfDate());
            return result.getId();
        } catch (Exception e) {
            log.error("[pivot-bundle] {} write failed: {}", bundle.getSymbol(), e.getMessage());
            return null;
        }
    }

    /** Write the run summary (one per sweep). */
    public String writeSummary(Long userId, String dateLabel, String body, List<String> extraTags) {
        List<String> tags = new java.util.ArrayList<>(List.of(
                "ai-trader-v2", "pivot-bundle", "pivot-bundle-summary",
                "date-" + dateLabel, "for-owner"));
        if (extraTags != null) tags.addAll(extraTags);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("date_label", dateLabel);
        try {
            MemsysWriteResult r = memsys.writeMemory(userId, body, "note", tags, meta,
                    null, null, true, true, null);
            log.info("[pivot-bundle] wrote summary id={} date={} body_chars={}",
                    r.getId(), dateLabel, body.length());
            return r.getId();
        } catch (Exception e) {
            log.error("[pivot-bundle] summary write failed: {}", e.getMessage());
            return null;
        }
    }
}
