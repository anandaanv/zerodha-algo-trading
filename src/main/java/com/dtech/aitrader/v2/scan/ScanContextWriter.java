package com.dtech.aitrader.v2.scan;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.aitrader.v2.memsys.MemsysWriteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pushes a {@link ScanContext} to memsys as a single `ai-trader-scan-context` memory,
 * returning the memory id so the orchestrator can stash it on each plan_group it then
 * creates (via plan_group metadata or a separate column).
 *
 * Tag layout:
 *   ai-trader-scan-context           ← primary discriminator
 *   ai-trader-v2
 *   symbol-{TICKER}                  ← always
 *   timeframe-{TF}                   ← e.g. timeframe-1D
 *   scan-id-{uuid}                   ← so all scans for one (user, symbol) can be threaded
 *   date-YYYY-MM-DD                  ← scan date
 *
 * Metadata:
 *   scan_id, user_id, symbol, timeframe, agent_version
 *   plan_group_ids: List<Long>       ← filled in by orchestrator AFTER it knows the ids
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ScanContextWriter {

    private static final int MEMSYS_MAX_CONTENT_CHARS = 256_000; // safety cap only — memsys is the authority on what fits

    private final MemsysClient memsys;
    private final ScanContextMarkdown markdown;

    /**
     * Writes the scan context to memsys at scan-START (before Agent 1 fires). Returns the
     * memory id so the orchestrator can link plan_groups back via metadata afterwards.
     * <p>
     * If memsys is unavailable, returns null and logs — the rest of the scan should not
     * be blocked by a memsys write failure (Agent 1 still runs; we just lose the audit trail
     * for this scan).
     */
    public String write(ScanContext ctx) {
        String body = markdown.render(ctx);
        // DEBUG: always dump the rendered bundle to /tmp for inspection regardless of memsys outcome
        try {
            java.nio.file.Files.writeString(
                    java.nio.file.Path.of("/tmp/bundle-" + safe(ctx.getSymbol()).toLowerCase() + ".md"),
                    body);
        } catch (Exception ignored) { /* best-effort */ }
        int originalLen = body.length();
        if (body.length() > MEMSYS_MAX_CONTENT_CHARS) {
            body = body.substring(0, MEMSYS_MAX_CONTENT_CHARS)
                    + "\n\n…[truncated by ScanContextWriter — " + originalLen
                    + " chars → " + MEMSYS_MAX_CONTENT_CHARS + "]";
            log.warn("[scan-context] bundle for symbol={} truncated: {} → {} chars",
                    ctx.getSymbol(), originalLen, body.length());
        }
        List<String> tags = List.of(
                "ai-trader-scan-context",
                "ai-trader-v2",
                "symbol-" + safe(ctx.getSymbol()),
                "timeframe-" + safe(ctx.getTimeframe()),
                "scan-id-" + safe(ctx.getScanId()),
                "date-" + (ctx.getScanTimestamp() == null ? "unknown"
                        : ctx.getScanTimestamp().toString().substring(0, 10))
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("scan_id", ctx.getScanId());
        metadata.put("user_id", ctx.getUserId());
        metadata.put("symbol", ctx.getSymbol());
        metadata.put("timeframe", ctx.getTimeframe());
        metadata.put("agent_version", ctx.getAgentVersion());
        metadata.put("scan_timestamp", String.valueOf(ctx.getScanTimestamp()));

        try {
            MemsysWriteResult result = memsys.writeMemory(
                    ctx.getUserId(), body, "fact", tags, metadata, /*parentId*/ null, /*supersedes*/ null, true);
            log.info("[scan-context] wrote memsys memory id={} for scan_id={} symbol={} body_chars={}",
                    result.getId(), ctx.getScanId(), ctx.getSymbol(), body.length());
            return result.getId();
        } catch (Exception e) {
            log.error("[scan-context] failed to write to memsys for scan_id={} symbol={} body_chars={}: {}",
                    ctx.getScanId(), ctx.getSymbol(), originalLen, e.getMessage());
            return null;
        }
    }

    /**
     * After plan_groups are created, link them back into the scan-context memory's metadata
     * so morning review can navigate from a scan to its trades. Best-effort; logs and continues.
     */
    public void linkPlanGroups(Long userId, String scanContextMemoryId, List<Long> planGroupIds) {
        if (scanContextMemoryId == null || scanContextMemoryId.isBlank()) return;
        try {
            Map<String, Object> patch = Map.of("plan_group_ids", planGroupIds);
            memsys.updateMemory(userId, scanContextMemoryId, null, null, patch, null);
        } catch (Exception e) {
            log.warn("[scan-context] failed to back-link plan_groups to {}: {}",
                    scanContextMemoryId, e.getMessage());
        }
    }

    private static String safe(Object o) {
        return o == null ? "unknown" : String.valueOf(o);
    }
}
