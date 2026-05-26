package com.dtech.aitrader.v2.rules;

import java.util.List;

/**
 * A deterministic predicate that scans a {@link SymbolContext} + the accumulated firings from
 * earlier passes, and emits zero or more {@link Firing} records. Pure function: same inputs in →
 * same firings out.
 *
 * <p>Per SPEC-004 ({@code 4e185036}): every rule declares its {@link #pass()} and
 * {@link #family()}. The multi-pass engine groups rules by pass, runs them in order 0→6, and
 * appends their firings to a shared store; later passes see all prior firings.
 *
 * <p>Every rule MUST:
 * <ol>
 *   <li>Read no bar / pivot beyond {@code ctx.asOf} — the loader enforces this, but rules should
 *       not stash references that survive the call.</li>
 *   <li>Tag every emitted firing with the correct family / pass / firesOn discriminators.</li>
 *   <li>Be a pure function — no random, no clock, no IO.</li>
 * </ol>
 */
public interface Rule {

    /** Stable identifier persisted on each firing, e.g. {@code "DOUBLE_BOTTOM_GEOMETRY"}. */
    String ruleId();

    /** Which of the 7 passes this rule runs in. Defaults to {@link Pass#P6_SYNTHESIS}. */
    default Pass pass() {
        return Pass.P6_SYNTHESIS;
    }

    /** Rule family — drives Pass-6 cross-family confluence detection. */
    default Family family() {
        return Family.SYNTHESIS;
    }

    /**
     * Evaluate the context + prior firings and emit any firings whose predicate is satisfied.
     *
     * @param ctx           point-in-time universe at {@code asOf}, leakage-guarded
     * @param priorFirings  firings emitted by all earlier passes in the current backtest as-of
     *                      (read-only; rules MUST NOT mutate)
     */
    List<Firing> evaluate(SymbolContext ctx, List<Firing> priorFirings);
}
