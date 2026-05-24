package com.dtech.aitrader.v2.regime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * Regime record per memsys schema {@code regime-record-v1} (canonical: a44c035a).
 *
 * <p>This is the seam between Haiku (which decides regime) and algotrade (which executes). The
 * record is ADVISORY — algotrade applies its OWN entry rule, sizing, and risk caps. Never treat
 * {@code targets_if_resolves}/{@code invalidation} as orders; they are structural references.
 * {@code conviction} is advisory — never let it override hard risk caps.
 *
 * <p>One regime record per watch-qualifying symbol per run; absence of a record = SKIP.
 */
@Data
@Builder
@NoArgsConstructor(access = AccessLevel.PUBLIC)
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RegimeRecord {
    /** Schema version literal — must equal {@code "regime-record-v1"}. */
    private String schema;

    /** NSE tradingsymbol the regime applies to. */
    private String symbol;

    /** When this read was made (ISO with TZ). */
    private OffsetDateTime as_of;

    /** {@code "nightly"} or {@code "intraday"} — which run produced this record. */
    private String source_run;

    /** Regime class enum. */
    private RegimeClass regime;

    /** Directional bias. {@link Bias#NEUTRAL} is ONLY valid with {@link RegimeClass#SQUEEZE_COILED}. */
    private Bias bias;

    /** Conviction — ADVISORY ONLY. Reader risk layer must NOT let this override caps. */
    private Conviction conviction;

    /** Expected bars-to-resolution at the time of the read. */
    private Integer horizon_days;

    /** Hard expiry of this read. Reader MUST discard or re-confirm past this. */
    private OffsetDateTime valid_until;

    /** Structural levels defining the regime. {@code invalidation} is mandatory. */
    private DefiningLevels defining_levels;

    /** Evidence trace — which stories agreed on this regime. */
    private Alignment alignment;

    /** Prose describing the condition algotrade should monitor. NOT an order. */
    private String trigger_to_watch;

    /** Free-form notes. Nullable. */
    private String notes;
}
