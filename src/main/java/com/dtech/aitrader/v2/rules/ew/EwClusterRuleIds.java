package com.dtech.aitrader.v2.rules.ew;

/**
 * Centralised rule-id constants for the per-TF instances of {@link EwClusterScanRule}.
 *
 * <p>Per owner directive {@code b954cd6e} (ratify of SPEC-010 Phase 1 1a/1b/1c): the cluster
 * scan generalises to a single TF-parameterised class instantiated once per TF (Week / Day /
 * OneHour). Each instance emits firings with its own rule-id so downstream rules
 * ({@link EwClusterConfluenceRule}, etc.) can target a specific TF's clusters without
 * accidentally mixing degrees.
 *
 * <p>{@link #WK} is preserved literally as {@code "EW_WK_CLUSTER_SCAN"} so that downstream
 * consumers that already filter by this id (and the blessed RELIANCE reference
 * {@code cde6bbc9}) continue to work unchanged.
 */
public final class EwClusterRuleIds {

    /** Weekly cluster scan instance — preserved literal so downstream EW rules see no change. */
    public static final String WK = "EW_WK_CLUSTER_SCAN";

    /** Daily cluster scan instance — feeds Phase-2 pattern cluster-respect on Day. */
    public static final String DAY = "EW_DAY_CLUSTER_SCAN";

    /** Hourly cluster scan instance — feeds Phase-2 pattern cluster-respect on Hr. */
    public static final String HOUR = "EW_HR_CLUSTER_SCAN";

    private EwClusterRuleIds() {}
}
