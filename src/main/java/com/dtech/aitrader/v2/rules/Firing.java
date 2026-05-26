package com.dtech.aitrader.v2.rules;

import com.dtech.aitrader.data.RuleFiring;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * In-flight rule firing produced by {@link Rule#evaluate(SymbolContext, java.util.List)}. The
 * backtest runner persists this as a {@link RuleFiring} row.
 *
 * <p>Carries enough fields to express ALL six {@link FiresOn} shapes, with most fields nullable
 * — a Pass-2 CANDIDATE doesn't carry trigger/invalidation/target (yet); a Pass-3 ELIMINATION
 * carries only refs + priorDelta. The {@link FiresOn} discriminator tells consumers which
 * fields are meaningful.
 *
 * <p>Only {@link FiresOn#VERDICT} firings (Pass 6 synthesis) carry the full level set + signature
 * — those are the outcome-bearing rows (Q7).
 */
@Value
@Builder(toBuilder = true)
public class Firing {

    // ── identity ───────────────────────────────────────────────────────────────

    /**
     * Content-addressable identifier — the firing's digest (see {@link FiringDigest}). Rules
     * leave this null; {@link MultiPassEngine} computes the digest after each rule emits its
     * firings and stamps it as the id. Identical-content firings get identical ids → engine-level
     * dedupe + DB-level UPSERT idempotency in one mechanism (owner O1 ratification {@code ada56b20}).
     *
     * <p>Tests may pass an explicit id to bypass the digest assignment (the engine respects a
     * pre-set id and only fills in {@code null} ones).
     */
    String id;

    String ruleId;
    String symbol;
    String tf;
    LocalDate asOf;

    // ── multi-pass discriminators (SPEC-004) ───────────────────────────────────
    Family family;
    Pass pass;
    FiresOn firesOn;

    /** Prior firings this firing depends on / acts upon (e.g. ELIMINATION refs the CANDIDATE). */
    List<String> refs;

    /** Discriminated-union prior adjustment — present on ELIMINATION/CLASSIFICATION/CONFIRMATION. */
    PriorDelta priorDelta;

    /** Initial prior for CANDIDATE firings (Pass-2 enumeration); ignored elsewhere. */
    Double basePrior;

    /** Feedback round — 1 for initial pass; ≥2 for spawn-from-contradiction work. */
    Integer roundNum;

    /** For spawned CANDIDATE firings only: SAME_ANCHOR (Pass-3 re-entry) vs RE_ANCHOR (Pass-1). */
    SpawnAnchorMode spawnAnchorMode;

    /** Rule-specific payload (geometry details, magnitudes, ratios, etc.). */
    Map<String, Object> payload;

    // ── verdict-shape fields (populated on VERDICT firings; legacy on pilot rules) ───────────────
    RuleFiring.Bias bias;
    Double triggerPrice;
    Double invalidationPrice;
    /** Nullable — some indicator rules may not emit a measured target. */
    Double targetPrice;

    /** Per-rule role given the probe — feeds the {@link #contextSignature}. */
    Role role;

    /** Controlled-vocab string produced by {@link ContextSignatureBuilder}. */
    String contextSignature;

    /** Composite conviction in [0,1] — weighted blend of {@link #convictionComponents}. */
    Double finalConviction;

    /** Per-component conviction scores ({"geometry":0.7, "sr":0.9, ...}) — audit trail. */
    Map<String, Double> convictionComponents;

    /** Frozen ContextProbeResult — same snapshot the role/signature were computed from. */
    ContextProbeResult context;

    /** Rule-specific evidence (pivot indices, cross-bar date, etc.) — JSON-friendly. */
    Map<String, Object> evidence;
}
