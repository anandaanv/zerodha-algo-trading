package com.dtech.aitrader.v2.scan;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The complete input bundle handed to Agent 1 for a single (user, symbol, timeframe) scan.
 *
 * Why this class exists: at scan time the orchestrator builds this bundle, sends it to
 * Agent 1 as a prompt, and Agent 1 outputs plan_groups + flags. Agent 1's output gets
 * mirrored to memsys as a "trade memory". But until now, Agent 1's INPUT was nowhere
 * persisted — so when a human reviews a trade in Claude.ai chat with Opus, Opus has no
 * way to see what Agent 1 actually saw. Was the drawn trendline in the bundle? Did the
 * pivot label make it? What journal notes were active?
 *
 * Per user requirement (2026-05-18): this entire bundle goes to memsys as a
 * "ai-trader-scan-context" memory, linked from each plan_group's metadata. Opus on
 * review can fetch the scan context to see exactly what Agent 1 saw.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScanContext {
    /** UUID we generate at scan-start so all related writes share a correlation id. */
    private String scanId;
    private Long userId;
    private String symbol;
    private String timeframe;
    private Instant scanTimestamp;
    private String agentVersion;

    // ── what the agent reads ─────────────────────────────────────────

    /** Drawings the trader has on this symbol's chart (TV save_load_adapter rows + intent annotations). */
    private List<Map<String, Object>> drawings;

    /** Per-drawing intent annotations (KEY_LEVEL / RETEST_ENTRY / etc.) — the structured intents. */
    private List<Map<String, Object>> annotations;

    /** Pivot labels (A, B, C, D, E) the trader has placed on the chart. */
    private List<Map<String, Object>> pivotLabels;

    /** Chronological free-form journal notes for this symbol (newest first). */
    private List<Map<String, Object>> journalNotes;

    /** Recent OHLC bars used to build the indicator + pattern view. */
    private List<Map<String, Object>> ohlcBars;

    /** Last-bar indicator snapshot (RSI, MACD, etc.) at the scan instant. */
    private Map<String, Object> indicators;

    /** Last-5-bar candle pattern classifications (bearish_engulfing, hammer, …). */
    private List<Map<String, Object>> candlePatterns;

    /** All active playbook rules loaded from memsys at scan-start. */
    private List<Map<String, Object>> playbookRules;

    /** Open flags on this symbol (BLOCKING / WARNING / INFO). */
    private List<Map<String, Object>> activeFlags;

    /** Flag resolutions from the last 30 days, included so Agent 1 doesn't re-raise dismissed concerns. */
    private List<Map<String, Object>> recentlyResolvedFlags;

    /** Existing WATCHING plan_groups for this (user, symbol). Agent 1 may UPDATE / SUPERSEDE / RETIRE them. */
    private List<Map<String, Object>> existingPlanGroups;

    /** Threaded chat comments the trader has added to active plan_groups since the previous scan. */
    private List<Map<String, Object>> userInputsSinceLastScan;

    /**
     * The exact text of the prompt rendered from this bundle and sent to Agent 1.
     * Stored verbatim so we have a perfect replay of what Agent 1 read.
     */
    private String renderedPromptText;
}
