package com.dtech.aitrader.v2.narrative.bundle;

import lombok.Builder;
import lombok.Value;

/**
 * Carrier for a rendered narrative-compact bundle, ready for memsys persistence.
 * Mirrors {@link com.dtech.aitrader.v2.scan.ScanContext} in role — a value object built by
 * {@link NarrativeBundleBuilder} and consumed by {@link NarrativeBundleWriter}.
 *
 * <p>One {@link NarrativeBundle} = one memsys memory = one (symbol, TF) row in the MTF run.
 */
@Value
@Builder
public class NarrativeBundle {
    /** memsys tenant owner of the memory; resolved by memsys via JWT.sub. */
    Long userId;

    /** Trading symbol (e.g. {@code "RELIANCE"}). */
    String symbol;

    /** Internal TF enum name ({@code "Week"|"Day"|"OneHour"|"FifteenMinute"}). */
    String tfEnum;

    /** Public TF label used in tags and the body header ({@code "weekly"|"daily"|"hourly"|"15min"}). */
    String tfLabel;

    /** The rendered compact-narrative markdown body (pipe table + bottom digest). */
    String body;

    /** Bar count actually fed to the engine after cutoff alignment + the 500-cap. */
    int barCount;

    /** ISO date of the last bar (e.g. {@code "2026-05-18"}); written into tags + metadata. */
    String lastBarDate;

    /** ISO date used in the {@code date-YYYY-MM-DD} tag — typically today, or owner-supplied. */
    String dateLabel;
}
