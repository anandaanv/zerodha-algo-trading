package com.dtech.aitrader.v2.rules.ew.signature;

import java.util.Map;

/**
 * A single leg in the candidate's pivot_assignment, paired with its computed sub-structure
 * character. Signature rules consume a {@code List<ObservedLeg>} and match it against their
 * declared {@link Signature}.
 *
 * @param label      EW role label e.g. {@code "A"}, {@code "B"}, {@code "C"}, {@code "W1"}.
 * @param startDate  IST date of the leg's start pivot.
 * @param startPrice price at the leg's start pivot.
 * @param endDate    IST date of the leg's end pivot, or {@code null} if leg is not yet complete.
 * @param endPrice   price at the leg's end pivot, or {@code null} if not complete.
 * @param character  computed leg-character — {@link LegCharacter#INDETERMINATE} when the leg is
 *                   not yet observable or has too few sub-pivots to classify.
 * @param evidence   raw discriminator signals (sub-pivot count, retest pass/fail, retrace pct,
 *                   etc.) — surfaced in firing payloads for audit. Map keys are stable for the
 *                   PHASE-A bridge.
 */
public record ObservedLeg(
        String label,
        String startDate,
        double startPrice,
        String endDate,
        Double endPrice,
        LegCharacter character,
        Map<String, Object> evidence) {

    /** True iff both endpoints are present and character has been computed. */
    public boolean isComplete() {
        return endDate != null && endPrice != null && character != LegCharacter.INDETERMINATE;
    }
}
