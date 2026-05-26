package com.dtech.aitrader.v2.rules;

/**
 * Discriminated-union prior adjustment carried by a firing that references a candidate. Per Q5 of
 * the convergence memo ({@code 9c60e777}): three kinds — {@link Kind#GRADUATED},
 * {@link Kind#CATEGORICAL_ELIMINATE}, {@link Kind#FLOOR_SET}.
 *
 * <p>Mapping (per the EW canonical rules):
 * <ul>
 *   <li>Rule 0.96 categorical (e.g. "B observed as 5-wave eliminates ABC")
 *       → {@link Kind#CATEGORICAL_ELIMINATE}</li>
 *   <li>Rule 0.95 leg examination (graduated mismatch)
 *       → {@link Kind#GRADUATED} with negative delta</li>
 *   <li>Rule 0.65 / Rule 0.5 "flag alternative prior ≥ 0.30"
 *       → two firings: a {@link Kind#GRADUATED} decrement on the original + a
 *       {@link Kind#FLOOR_SET} on the spawned alternative</li>
 * </ul>
 *
 * <p>Use the three static factories to construct — the constructor is private to keep the
 * invariants tight (only the matching field set for each kind; the others stay null).
 */
public final class PriorDelta {

    public enum Kind { GRADUATED, CATEGORICAL_ELIMINATE, FLOOR_SET }

    private final Kind kind;
    private final Double graduatedDelta;  // present only for GRADUATED
    private final Double floorValue;      // present only for FLOOR_SET
    private final String reason;
    private final String ruleRef;

    private PriorDelta(Kind kind, Double graduatedDelta, Double floorValue,
                        String reason, String ruleRef) {
        this.kind = kind;
        this.graduatedDelta = graduatedDelta;
        this.floorValue = floorValue;
        this.reason = reason;
        this.ruleRef = ruleRef;
    }

    /** Additive prior adjustment in {@code [-1.0, +1.0]}; fold clamps the result to {@code [0,1]}. */
    public static PriorDelta graduated(double delta, String reason, String ruleRef) {
        return new PriorDelta(Kind.GRADUATED, delta, null, reason, ruleRef);
    }

    /** Hard elimination — sets {@code eliminated=true} and {@code live_prior=0} in the fold. */
    public static PriorDelta eliminate(String reason, String ruleRef) {
        return new PriorDelta(Kind.CATEGORICAL_ELIMINATE, null, null, reason, ruleRef);
    }

    /** Raise prior to at least {@code floor} (never lowers an already-higher prior). */
    public static PriorDelta floorSet(double floor, String reason, String ruleRef) {
        return new PriorDelta(Kind.FLOOR_SET, null, floor, reason, ruleRef);
    }

    public Kind kind() { return kind; }
    public Double graduatedDelta() { return graduatedDelta; }
    public Double floorValue() { return floorValue; }
    public String reason() { return reason; }
    public String ruleRef() { return ruleRef; }
}
