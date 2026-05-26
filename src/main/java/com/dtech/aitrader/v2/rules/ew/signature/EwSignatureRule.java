package com.dtech.aitrader.v2.rules.ew.signature;

import com.dtech.aitrader.v2.rules.SymbolContext;

import java.util.List;

/**
 * <h2>The leg-character-signature rule interface — the future EW rule-DSL grammar.</h2>
 *
 * <p>Per owner's reframe ({@code 159ba913}) of the pre-conclusion gap and the DSL roadmap
 * ({@code eec5c5dd}): EW interpretations are CONDITIONAL — a hypothesis is valid because its
 * expected leg-character sequence is not yet contradicted by observed structure. The engine
 * enumerates ALL admissible hypotheses, keeps each ALIVE until invalidated, and emits a
 * LEVEL-MAP (watch + invalidation per live hypothesis) — NOT a single weighted verdict.
 *
 * <p>Every EW rule reduces to four declarative pieces:
 * <ol>
 *   <li><b>{@link #signature()}</b> — expected leg-character sequence
 *       (e.g. zigzag = {@code FIVE-THREE-FIVE}).</li>
 *   <li><b>{@link #evaluate}</b> — admission condition (partial-match OK) /
 *       invalidation condition (a determinate observed character contradicts the signature).</li>
 *   <li><b>{@link #deriveLevels}</b> — watch + invalidation price levels contributed to the
 *       engine's level-map.</li>
 *   <li><b>{@link #id} / {@link #formName}</b> — identifiers for telemetry and audit.</li>
 * </ol>
 *
 * <p>That four-part shape IS the eventual DSL grammar. When the rule shape stabilises (decision
 * {@code eec5c5dd} stability gate: ~3 consecutive new rules with no interface change), this
 * interface is extracted to a YAML/JSON schema:
 *
 * <pre>{@code
 *   # zigzag rule, declarative form
 *   id: zigzag-5-3-5
 *   form: zigzag
 *   signature:
 *     legs:     [A, B, C]
 *     expected: [FIVE, THREE, FIVE]
 *   derived_levels:
 *     watch:        "C target = B_end ± |A magnitude|"
 *     invalidation: "above A_start kills zigzag"
 * }</pre>
 *
 * <p>Until the DSL ships, rules implement this interface in Java. Each new rule that requires a
 * new interface method or field RESETS the stability count — the DSL extraction is only worth it
 * when the interface has stopped moving.
 *
 * <h2>Engine integration (no leakage)</h2>
 *
 * <p>Signature rules are PURE plugins — they do not declare a {@code Pass} or {@code Family} or
 * emit firings directly. The {@code EwSignatureEvaluationRule} Pass-5 orchestrator iterates the
 * registered signature rules, computes observed legs from the candidate's {@code
 * pivot_assignment}, evaluates each rule, and emits one CONFIRMATION firing per
 * (candidate, rule). This separation keeps signature rules expressible as data (DSL goal) and
 * isolates them from engine internals.
 *
 * <h2>Provisional status (PHASE A)</h2>
 *
 * <p>The PHASE-A leg-character examiner (see {@link LegCharacterExaminer}) uses a retest +
 * retrace bridge as a proxy for the pattern family's eventual impulsive/corrective shape
 * classifier. Until PHASE B lands the classifier, every signature evaluation carries
 * {@code provisional: true} in its firing payload. Rules implementing this interface should NOT
 * embed shape detection — they should consume the character produced by the examiner.
 */
public interface EwSignatureRule {

    /** Stable rule identifier for audit + signature-IDs (e.g. {@code "zigzag-5-3-5"}). */
    String id();

    /**
     * Form label surfaced in firings + level-map. Standard values: {@code "zigzag"},
     * {@code "flat"}, {@code "triangle"}, {@code "impulse"}, {@code "bigger-impulse"},
     * {@code "truncated-c"}. New forms add new values; the registry does not enforce a closed
     * list (additive growth is the whole point of the framework).
     */
    String formName();

    /** Declared signature — the expected leg-character sequence + leg labels. */
    Signature signature();

    /**
     * <b>Admission / invalidation check.</b> Given the candidate's observed legs (in the same
     * order as {@link Signature#legLabels()}, padded with INDETERMINATE for legs not yet
     * observable), return whether the hypothesis is:
     *
     * <ul>
     *   <li>{@link AdmissionState#ADMITTED} — observed legs match (possibly partial) the
     *       signature's leading positions. The hypothesis is LIVE.</li>
     *   <li>{@link AdmissionState#INVALIDATED} — a determinate observed character contradicts
     *       the signature at its position. The hypothesis is dead.</li>
     *   <li>{@link AdmissionState#PENDING} — not enough observed legs yet to decide.</li>
     * </ul>
     *
     * <p>The {@code observed} list size MUST equal {@code signature().legCount()}. Callers pad
     * absent legs with {@link LegCharacter#INDETERMINATE} ObservedLegs.
     */
    AdmissionResult evaluate(List<ObservedLeg> observed);

    /**
     * <b>Level-map contribution.</b> Given the candidate's observed legs + the symbol context
     * (for current price / cluster references), return the watch + invalidation price levels
     * this hypothesis contributes to the engine's output level-map.
     *
     * <p>Watch levels: where the next leg of THIS hypothesis would complete (e.g. for zigzag in
     * progress, the C target). Invalidation levels: where THIS hypothesis dies (e.g. above
     * A_start for a corrective zigzag).
     *
     * <p>Called only when {@link #evaluate} returns {@link AdmissionState#ADMITTED}.
     */
    DerivedLevels deriveLevels(List<ObservedLeg> observed, SymbolContext ctx);
}
