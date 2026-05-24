package com.dtech.aitrader.v2.regime.reader;

import com.dtech.aitrader.v2.memsys.MemsysClient;
import com.dtech.aitrader.v2.memsys.MemsysMemory;
import com.dtech.aitrader.v2.regime.RegimeRecord;
import com.dtech.aitrader.v2.regime.RegimeRecordValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Component 2 of the watchlist→regime pipeline.
 *
 * <p>Reads the regime records that the nightly Haiku worker wrote, validates each strictly
 * against {@code regime-record-v1} (schema {@code a44c035a}), drops expired records, and exposes
 * the validated set as today's watchlist.
 *
 * <p>The reader is intended to be invoked once each morning pre-market by the strategy layer.
 * It is also exposed via a REST endpoint for manual fire / dry-run inspection.
 *
 * <h2>Contract enforced here</h2>
 * <ul>
 *   <li>Records that fail validation are LOGGED and REJECTED — never returned to the strategy
 *       layer. Owner rule: "do NOT trade on a record that fails validation."</li>
 *   <li>Records with {@code valid_until ≤ now} are DROPPED — owner rule: "discard/re-confirm
 *       stale records."</li>
 *   <li>Records are READ-ONLY: this component never mutates them.</li>
 *   <li>This component is the SEAM: callers downstream see {@link RegimeRecord} objects and
 *       must apply their own entry rule, sizing, and risk caps. Conviction is advisory only —
 *       NEVER use it to override hard risk limits.</li>
 * </ul>
 *
 * <p>Config:
 * <ul>
 *   <li>{@code regime.reader.user-id} — memsys tenant (default 1)</li>
 *   <li>{@code regime.reader.max-records} — safety cap on records returned per call (default 500)</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegimeRecordReader {

    private final MemsysClient memsys;
    private final RegimeRecordValidator validator;

    @Value("${regime.reader.user-id:1}")
    private Long readerUserId;

    @Value("${regime.reader.max-records:500}")
    private int maxRecords;

    /**
     * Fetches and validates regime records for the watchlist matching {@code dateLabel}. Defaults
     * to today (IST). Returns a {@link Result} containing the validated records + counts +
     * rejection reasons (for audit / debugging).
     */
    public Result read(String dateLabel) {
        return read(dateLabel, null);
    }

    /**
     * Same as {@link #read(String)} but filters by {@code sourceRun} ({@code "nightly"} or
     * {@code "intraday"}). Pass {@code null} for both sources.
     */
    public Result read(String dateLabel, String sourceRun) {
        if (dateLabel == null || dateLabel.isBlank()) {
            dateLabel = LocalDate.now(ZoneId.of("Asia/Kolkata")).toString();
        }
        log.info("[regime-reader] read date={} sourceRun={}", dateLabel, sourceRun);

        List<String> tags = new ArrayList<>(List.of(
                "ai-trader-v2",
                "regime-record",
                "watchlist",
                "date-" + dateLabel));
        if (sourceRun != null && !sourceRun.isBlank()) {
            tags.add("source-" + sourceRun);
        }

        List<MemsysMemory> hits;
        try {
            hits = memsys.searchMemories(
                    readerUserId,
                    "regime record watchlist",
                    tags,
                    /*type*/ null,
                    /*parentId*/ null,
                    /*since*/ null,
                    /*until*/ null,
                    /*limit*/ maxRecords);
        } catch (Exception e) {
            log.error("[regime-reader] memsys search failed date={}: {}", dateLabel, e.getMessage());
            return new Result(dateLabel, 0, 0, 0, 0, List.of(), List.of(), e.getMessage());
        }
        if (hits == null) hits = List.of();

        OffsetDateTime now = OffsetDateTime.now(ZoneId.of("Asia/Kolkata"));
        List<RegimeRecord> valid = new ArrayList<>();
        List<RejectedRecord> rejected = new ArrayList<>();
        int expired = 0;

        for (MemsysMemory mem : hits) {
            String memId = mem.getId();
            String body = mem.getContent();
            RegimeRecordValidator.Result vr = validator.parse(body, memId);
            if (vr instanceof RegimeRecordValidator.Invalid inv) {
                rejected.add(new RejectedRecord(memId, inv.errors()));
                continue;
            }
            RegimeRecord r = ((RegimeRecordValidator.Valid) vr).record();
            if (validator.isExpired(r, now)) {
                expired++;
                log.debug("[regime-reader] {} dropped — expired (valid_until={} < now={})",
                        memId, r.getValid_until(), now);
                continue;
            }
            valid.add(r);
        }

        Result result = new Result(dateLabel, hits.size(), valid.size(),
                rejected.size(), expired, valid, rejected, null);
        log.info("[regime-reader] done date={} found={} valid={} rejected={} expired={}",
                dateLabel, hits.size(), valid.size(), rejected.size(), expired);
        return result;
    }

    /**
     * Outcome of a read: counts + the validated record set + the rejected ones (for audit).
     */
    public record Result(
            String dateLabel,
            int found,
            int valid,
            int rejected,
            int expired,
            List<RegimeRecord> records,
            List<RejectedRecord> rejections,
            String error
    ) {}

    public record RejectedRecord(String memoryId, List<String> errors) {}
}
