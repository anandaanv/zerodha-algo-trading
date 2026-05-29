package com.dtech.aitrader.v2.rules.ew.dwell;

/**
 * Continuation-role label for a {@link DwellPivot}, per owner refinement {@code f1201a45}.
 *
 * <p><b>NOT a swing-high/low claim.</b> A dwell pivot is a SHELF in a trend, not a turn. The
 * direction encodes which trend-structure the shelf participates in:
 * <ul>
 *   <li>{@link #HH} — dwell formed in an uptrend; price respected the shelf and continued up
 *       (acts as support / launch-shelf, participating in a higher-high structure).</li>
 *   <li>{@link #LL} — dwell formed in a downtrend; price capped at the shelf and continued down
 *       (acts as resistance / pause-shelf, participating in a lower-low structure).</li>
 *   <li>{@link #INDETERMINATE} — the dwell sits at the right edge of the data (forming) or no
 *       break has occurred within the lookforward window. Consumers should treat this as "not yet
 *       classified" rather than "neither direction".</li>
 * </ul>
 *
 * <p>Consumers (cluster scan, tradability layer) MUST NOT splice a dwell-HH into the alternating
 * H-L-H-L reversal-pivot series — owner constraint {@code 59fa728f} forbids this. The HH/LL label
 * lets consumers use direction without mistaking the shelf for a turn.
 */
public enum Direction {
    HH,
    LL,
    INDETERMINATE
}
