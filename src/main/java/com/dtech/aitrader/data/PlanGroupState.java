package com.dtech.aitrader.data;

/**
 * Lifecycle states for a v2 plan_group.
 *
 * <ul>
 *   <li>{@link #WATCHING} — at least one branch monitored by the deterministic trigger.</li>
 *   <li>{@link #TRIGGERED} — one branch fired; siblings auto-invalidated; group locked.</li>
 *   <li>{@link #EXPIRED} — {@code valid_until} elapsed without any trigger.</li>
 *   <li>{@link #INVALIDATED} — source structure broken; all branches die.</li>
 *   <li>{@link #SUPERSEDED} — a newer plan_group with a materially different hypothesis took over.</li>
 * </ul>
 *
 * State transitions are orchestrated by the v2 orchestrator service and mirrored as
 * tag changes on the companion memsys trade memory (see AI Trader v2 — Plan Groups & Branches Schema v1.1).
 */
public enum PlanGroupState {
    WATCHING,
    TRIGGERED,
    EXPIRED,
    INVALIDATED,
    SUPERSEDED;
}
