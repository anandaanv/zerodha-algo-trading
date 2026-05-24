package com.dtech.aitrader.v2.narrative.bundle;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.aitrader.v2.memsys.MemsysWriteResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pushes a {@link NarrativeBundle} to memsys as a single {@code indicator-narrative} memory.
 * Mirrors {@link com.dtech.aitrader.v2.scan.ScanContextWriter} in role — same memsys plumbing,
 * different memory shape + tag layout.
 *
 * <p>Tag layout per owner b3ff4ca0:
 * <pre>
 *   ai-trader-v2
 *   indicator-narrative
 *   narrative-compact
 *   mtf-runup-v2
 *   symbol-{TICKER}
 *   tf-{weekly|daily|hourly|15min}
 *   date-{YYYY-MM-DD}
 *   for-owner-validation
 * </pre>
 *
 * <p>Always written with {@code force_new=true} — embedding-similarity dedup would otherwise
 * collapse same-symbol cross-TF narratives (lesson from the v1 run, see memsys a4e21cb9).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NarrativeBundleWriter {

    private static final int MEMSYS_MAX_CONTENT_CHARS = 200_000;

    private final MemsysClient memsys;

    /**
     * Write one bundle to memsys. Returns the memsys memory id on success, or {@code null} on
     * failure (logs but does not throw — caller is the orchestrator and continues on error).
     */
    public String write(NarrativeBundle bundle) {
        String body = bundle.getBody();
        if (body == null || body.isBlank()) {
            log.warn("[narrative-bundle] {} {} — empty body; skipping write", bundle.getSymbol(), bundle.getTfLabel());
            return null;
        }
        if (body.length() > MEMSYS_MAX_CONTENT_CHARS) {
            log.warn("[narrative-bundle] {} {} — body {} chars > {} cap; truncating",
                    bundle.getSymbol(), bundle.getTfLabel(), body.length(), MEMSYS_MAX_CONTENT_CHARS);
            body = body.substring(0, MEMSYS_MAX_CONTENT_CHARS)
                    + "\n…[truncated by NarrativeBundleWriter]";
        }

        // asof-<DATE> per owner b5ffa13f issue-3: stamp the narrative's actual last-bar date so
        // consumers can detect cross-TF misalignment (when a single intraday TF lags the others
        // beyond what the cutoff trim can fix).
        String asOf = bundle.getLastBarDate() != null ? bundle.getLastBarDate() : bundle.getDateLabel();
        List<String> tags = List.of(
                "ai-trader-v2",
                "indicator-narrative",
                "narrative-compact",
                "mtf-runup-v2",
                "symbol-" + bundle.getSymbol(),
                "tf-" + bundle.getTfLabel(),
                "date-" + bundle.getDateLabel(),
                "asof-" + asOf,
                "for-owner-validation"
        );

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("symbol", bundle.getSymbol());
        metadata.put("tf_enum", bundle.getTfEnum());
        metadata.put("tf_label", bundle.getTfLabel());
        metadata.put("bar_count", bundle.getBarCount());
        metadata.put("last_bar_date", bundle.getLastBarDate());
        metadata.put("agent_version", "narrative-v2");
        metadata.put("body_chars", body.length());

        try {
            MemsysWriteResult result = memsys.writeMemory(
                    bundle.getUserId(), body, "note", tags, metadata,
                    /*parentId*/ null, /*supersedes*/ null,
                    /*forceNew*/ true,
                    /*indexable*/ true,
                    /*expiresAt*/ null);
            log.info("[narrative-bundle] wrote memsys id={} symbol={} tf={} body_chars={} last_bar={}",
                    result.getId(), bundle.getSymbol(), bundle.getTfLabel(), body.length(),
                    bundle.getLastBarDate());
            return result.getId();
        } catch (Exception e) {
            log.error("[narrative-bundle] write failed symbol={} tf={} body_chars={}: {}",
                    bundle.getSymbol(), bundle.getTfLabel(), body.length(), e.getMessage());
            return null;
        }
    }

    /**
     * Write a final summary memory (mtf-runup-v2-summary) with the list of per-bundle UUIDs +
     * data caveats. Owner b3ff4ca0 deliverable: "Post a 1-memory summary at the end with the
     * UUIDs (or the per-stock MTF-index UUIDs) and any data caveats."
     *
     * <p>{@code extraSummaryTags} are appended to the default tags — e.g. {@code "fno-full"} for
     * the FNO universe sweep so consumers can find the right summary among multiple.
     */
    public String writeSummary(Long userId, String dateLabel, String body, List<String> extraSummaryTags) {
        List<String> baseTags = new ArrayList<>(List.of(
                "ai-trader-v2",
                "indicator-narrative",
                "narrative-compact",
                "mtf-runup-v2",
                "mtf-runup-v2-summary",
                "date-" + dateLabel,
                "for-owner-validation"
        ));
        if (extraSummaryTags != null) baseTags.addAll(extraSummaryTags);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("date_label", dateLabel);
        metadata.put("agent_version", "narrative-v2");

        try {
            MemsysWriteResult result = memsys.writeMemory(
                    userId, body, "note", baseTags, metadata,
                    null, null, /*forceNew*/ true, /*indexable*/ true, null);
            log.info("[narrative-bundle] wrote summary id={} date={} body_chars={} tags={}",
                    result.getId(), dateLabel, body.length(), baseTags);
            return result.getId();
        } catch (Exception e) {
            log.error("[narrative-bundle] summary write failed date={}: {}", dateLabel, e.getMessage());
            return null;
        }
    }

    /** Backwards-compatible no-extra-tags overload (used by the 15-stock watchlist path). */
    public String writeSummary(Long userId, String dateLabel, String body) {
        return writeSummary(userId, dateLabel, body, null);
    }

    /**
     * Write the canonical {@code fno-universe} memory at the start of a full-FNO sweep — captures
     * the symbol list, as-of date, and any skipped symbols. Owner-required deliverable.
     */
    public String writeFnoUniverse(Long userId, String asOfDate,
                                    List<String> symbols, List<String> skippedSymbols,
                                    String skipReasonsBody) {
        StringBuilder sb = new StringBuilder();
        sb.append("# NSE F&O underlying universe — as of ").append(asOfDate).append("\n\n");
        sb.append("Resolved from local `instrument` master (NFO-FUT distinct `name` ∩ NSE-EQ tradingsymbol). ");
        sb.append("Indices (NIFTY/BANKNIFTY/etc.) excluded — no tradable equity series.\n\n");
        sb.append("Total candidates: ").append(symbols.size() + (skippedSymbols == null ? 0 : skippedSymbols.size()))
                .append("  |  Included: ").append(symbols.size())
                .append("  |  Skipped (< min daily bars): ").append(skippedSymbols == null ? 0 : skippedSymbols.size())
                .append("\n\n");
        sb.append("## Included symbols (").append(symbols.size()).append(")\n");
        sb.append(String.join(", ", symbols)).append("\n");
        if (skippedSymbols != null && !skippedSymbols.isEmpty()) {
            sb.append("\n## Skipped symbols\n");
            sb.append(skipReasonsBody == null ? String.join(", ", skippedSymbols) : skipReasonsBody);
            sb.append("\n");
        }

        List<String> tags = List.of(
                "ai-trader-v2",
                "fno-universe",
                "date-" + asOfDate
        );
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("as_of_date", asOfDate);
        metadata.put("symbol_count", symbols.size());
        metadata.put("skipped_count", skippedSymbols == null ? 0 : skippedSymbols.size());
        metadata.put("source", "instrument.NFO-FUT ∩ NSE-EQ");

        try {
            MemsysWriteResult result = memsys.writeMemory(
                    userId, sb.toString(), "fact", tags, metadata,
                    null, null, /*forceNew*/ true, /*indexable*/ true, null);
            log.info("[narrative-bundle] wrote fno-universe id={} as_of={} included={} skipped={}",
                    result.getId(), asOfDate, symbols.size(), skippedSymbols == null ? 0 : skippedSymbols.size());
            return result.getId();
        } catch (Exception e) {
            log.error("[narrative-bundle] fno-universe write failed as_of={}: {}", asOfDate, e.getMessage());
            return null;
        }
    }
}
